#!/usr/bin/env python3
"""
Kanta — map style generator (KANTA_SPEC.md §2 "Map", §3.4).

Takes OpenFreeMap's `positron` style as a base and produces the two Kanta styles:

    app/src/main/assets/map/kanta_light.json
    app/src/main/assets/map/kanta_dark.json

Both are valid MapLibre styles you can open directly in Maputnik. What makes them
Kanta's is a `metadata["kanta:palette"]` block at the top of each file holding
EVERY colour the map uses, and a `metadata["kanta:paint"]` tag on each layer
saying which palette entry feeds which paint property. `KantaMapStyle.kt` applies
the palette over the layers at load time, so changing a colour in the palette
changes the map — that one block is the single place to edit.

Standard library only.

    python3 tools/map_style/build_styles.py
"""

from __future__ import annotations

import json
import urllib.request
from pathlib import Path

POSITRON_URL = "https://tiles.openfreemap.org/styles/positron"
CACHE = Path("tools/map_style/.cache/positron.json")
OUT_DIR = Path("app/src/main/assets/map")

# -------------------------------------------------------------------------------------------
# Palettes — the only place colours are defined.
#
# Light values follow §3.1 where the app and the map share a surface (background,
# park). The street greys are deliberately low-contrast: §3 says the map is the
# hero but the chrome is quiet, and every drop of saturation is being saved for
# the container markers.
# -------------------------------------------------------------------------------------------

LIGHT = {
    "background":        "#F3F5F2",   # matches the app background (§3.1)
    "water":             "#C4D0D5",   # muted blue-grey
    "waterway":          "#BCC9CF",
    "waterLabel":        "#6A7F8A",
    "park":              "#E4ECE3",   # spec §3.4 brief: very pale green
    "wood":              "#DDE6DC",
    "residential":       "#EDEEEA",
    "building":          "#E7E8E3",
    "buildingOutline":   "#DCDED8",
    "aeroway":           "#EFEFEC",
    "path":              "#E6E7E3",
    "roadMinor":         "#E4E5E1",
    "roadMajorCasing":   "#DADBD6",
    "roadMajorInner":    "#FFFFFF",
    "roadMajorSubtle":   "#E0E1DC",
    "roadMotorwayCasing": "#D6D7D2",
    "roadMotorwaySubtle": "#E2E3DE",
    "pier":              "#EDEEEA",
    "railway":           "#DEDFDA",
    "railwayDash":       "#F5F6F3",
    "boundary":          "#C3C8C1",
    "labelText":         "#5E6A62",   # onSurfaceMuted (§3.1)
    "labelTextStrong":   "#141A16",   # onSurface (§3.1)
    "labelHalo":         "#F3F5F2",
    "roadLabel":         "#77837A",
}

DARK = {
    "background":        "#0F1411",   # app dark background (§3.1)
    "water":             "#16242A",
    "waterway":          "#1A2A31",
    "waterLabel":        "#6E8B96",
    "park":              "#16201A",   # spec §3.4 brief
    "wood":              "#141D18",
    "residential":       "#131A16",
    "building":          "#19211C",
    "buildingOutline":   "#212B24",
    "aeroway":           "#151C18",
    "path":              "#1B241E",
    "roadMinor":         "#1E2822",
    "roadMajorCasing":   "#222D26",
    "roadMajorInner":    "#2A362E",
    "roadMajorSubtle":   "#212B25",
    "roadMotorwayCasing": "#25312A",
    "roadMotorwaySubtle": "#2A362E",
    "pier":              "#19211C",
    "railway":           "#222B25",
    "railwayDash":       "#161E19",
    "boundary":          "#2B342E",
    "labelText":         "#98A49C",   # onSurfaceMuted dark (§3.1)
    "labelTextStrong":   "#E7ECE8",   # onSurface dark (§3.1)
    "labelHalo":         "#0F1411",
    "roadLabel":         "#8A968D",
}

# -------------------------------------------------------------------------------------------
# Which palette entry drives which layer's paint property.
# -------------------------------------------------------------------------------------------

LAYER_PAINT = {
    "background":                    {"background-color": "background"},
    "park":                          {"fill-color": "park"},
    "water":                         {"fill-color": "water"},
    "landcover_ice_shelf":           {"fill-color": "residential"},
    "landcover_glacier":             {"fill-color": "residential"},
    "landuse_residential":           {"fill-color": "residential"},
    "landcover_wood":                {"fill-color": "wood"},
    "waterway":                      {"line-color": "waterway"},
    "building":                      {"fill-color": "building",
                                      "fill-outline-color": "buildingOutline"},
    "tunnel_motorway_casing":        {"line-color": "roadMotorwayCasing"},
    "tunnel_motorway_inner":         {"line-color": "roadMotorwaySubtle"},
    "aeroway-taxiway":               {"line-color": "aeroway"},
    "aeroway-runway-casing":         {"line-color": "aeroway"},
    "aeroway-area":                  {"fill-color": "aeroway"},
    "aeroway-runway":                {"line-color": "roadMajorInner"},
    "road_area_pier":                {"fill-color": "pier"},
    "road_pier":                     {"line-color": "pier"},
    "highway_path":                  {"line-color": "path"},
    "highway_minor":                 {"line-color": "roadMinor"},
    "highway_major_casing":          {"line-color": "roadMajorCasing"},
    "highway_major_inner":           {"line-color": "roadMajorInner"},
    "highway_major_subtle":          {"line-color": "roadMajorSubtle"},
    "highway_motorway_casing":       {"line-color": "roadMotorwayCasing"},
    "highway_motorway_subtle":       {"line-color": "roadMotorwaySubtle"},
    "highway_motorway_bridge_casing": {"line-color": "roadMotorwayCasing"},
    "railway_transit":               {"line-color": "railway"},
    "railway_transit_dashline":      {"line-color": "railwayDash"},
    "railway_service":               {"line-color": "railway"},
    "railway_service_dashline":      {"line-color": "railwayDash"},
    "railway":                       {"line-color": "railway"},
    "railway_dashline":              {"line-color": "railwayDash"},
    "boundary_3":                    {"line-color": "boundary"},
    "boundary_2":                    {"line-color": "boundary"},
    "boundary_disputed":             {"line-color": "boundary"},
    "waterway_line_label":           {"text-color": "waterLabel", "text-halo-color": "labelHalo"},
    "water_name_point_label":        {"text-color": "waterLabel", "text-halo-color": "labelHalo"},
    "water_name_line_label":         {"text-color": "waterLabel", "text-halo-color": "labelHalo"},
    "highway-name-path":             {"text-color": "roadLabel", "text-halo-color": "labelHalo"},
    "highway-name-minor":            {"text-color": "roadLabel", "text-halo-color": "labelHalo"},
    "highway-name-major":            {"text-color": "roadLabel", "text-halo-color": "labelHalo"},
    "airport":                       {"text-color": "labelText", "text-halo-color": "labelHalo"},
    "label_other":                   {"text-color": "labelText", "text-halo-color": "labelHalo"},
    "label_village":                 {"text-color": "labelText", "text-halo-color": "labelHalo"},
    "label_town":                    {"text-color": "labelTextStrong", "text-halo-color": "labelHalo"},
    "label_state":                   {"text-color": "labelText", "text-halo-color": "labelHalo"},
    "label_city":                    {"text-color": "labelTextStrong", "text-halo-color": "labelHalo"},
    "label_city_capital":            {"text-color": "labelTextStrong", "text-halo-color": "labelHalo"},
    "label_country_3":               {"text-color": "labelText", "text-halo-color": "labelHalo"},
    "label_country_2":               {"text-color": "labelText", "text-halo-color": "labelHalo"},
    "label_country_1":               {"text-color": "labelTextStrong", "text-halo-color": "labelHalo"},
}

# §7 / user brief: Cyrillic labels preferred. `name:mk` where OSM has it, the
# local `name` otherwise — which in Skopje is also Cyrillic, so this degrades
# to the right thing rather than to English.
NAME_EXPRESSION = ["coalesce", ["get", "name:mk"], ["get", "name"]]

# OpenFreeMap serves only Noto Sans glyphs. Nunito (§3.2) is not available and
# would mean hosting our own glyph PBFs; Noto Sans is the closest humanist sans
# with complete Cyrillic and Albanian coverage. The app's OWN text is Nunito —
# this applies to map labels only.
FONT_REGULAR = ["Noto Sans Regular"]
FONT_BOLD = ["Noto Sans Bold"]


def fetch_base() -> dict:
    if CACHE.exists():
        print(f"  using cached base style: {CACHE}")
        return json.loads(CACHE.read_text())

    print(f"  downloading {POSITRON_URL}")
    request = urllib.request.Request(
        POSITRON_URL, headers={"User-Agent": "kanta-map-style/1.0"}
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        payload = json.loads(response.read().decode("utf-8"))

    CACHE.parent.mkdir(parents=True, exist_ok=True)
    CACHE.write_text(json.dumps(payload))
    return payload


def build(base: dict, palette: dict[str, str], name: str) -> dict:
    style = json.loads(json.dumps(base))  # deep copy

    style["name"] = name
    # The palette sits at the very top of the file. Python dicts keep insertion
    # order and json.dump honours it, so this is literally the first thing you
    # see when you open the file.
    style = {
        "version": style["version"],
        "name": name,
        "metadata": {
            "kanta:palette": palette,
            "kanta:note": (
                "EVERY map colour lives in kanta:palette above. Edit a value there and the "
                "whole map follows — KantaMapStyle.kt applies the palette over the layers at "
                "load time. Layer paint values below are kept in sync so the file still opens "
                "correctly in Maputnik; if you change a colour in Maputnik, copy it up into "
                "the palette or your change will be overwritten at runtime. "
                "See tools/map_style/MAPUTNIK.md."
            ),
            "kanta:source": "Derived from OpenFreeMap 'positron'. Data (c) OpenStreetMap contributors.",
        },
        "sources": style["sources"],
        "sprite": style["sprite"],
        "glyphs": style["glyphs"],
        "layers": style["layers"],
    }

    for layer in style["layers"]:
        layer_id = layer.get("id")
        mapping = LAYER_PAINT.get(layer_id)

        if mapping:
            paint = layer.setdefault("paint", {})
            for prop, key in mapping.items():
                paint[prop] = palette[key]
            # Tag the layer so the runtime knows which palette entry owns which
            # property, and so a human reading the JSON can see the link.
            layer.setdefault("metadata", {})["kanta:paint"] = mapping

        layout = layer.get("layout")
        if layout and "text-field" in layout:
            layout["text-field"] = NAME_EXPRESSION
            # Keep the base style's weight choice, just swap the family.
            existing = layout.get("text-font") or FONT_REGULAR
            layout["text-font"] = FONT_BOLD if "Bold" in existing[0] else FONT_REGULAR

    return style


def main() -> int:
    print("Kanta — map style generator")
    base = fetch_base()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    for palette, filename, label in (
        (LIGHT, "kanta_light.json", "Kanta Light"),
        (DARK, "kanta_dark.json", "Kanta Dark"),
    ):
        style = build(base, palette, label)
        path = OUT_DIR / filename
        path.write_text(json.dumps(style, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        size_kb = path.stat().st_size / 1024
        print(f"  wrote {path} ({size_kb:.0f} KB, {len(style['layers'])} layers, "
              f"{len(palette)} palette entries)")

    print("\nOpen either file in Maputnik to edit visually — see tools/map_style/MAPUTNIK.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
