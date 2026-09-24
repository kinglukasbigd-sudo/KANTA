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
import re
import urllib.request
from pathlib import Path

POSITRON_URL = "https://tiles.openfreemap.org/styles/positron"
CACHE = Path("tools/map_style/.cache/positron.json")
OUT_DIR = Path("app/src/main/assets/map")

# -------------------------------------------------------------------------------------------
# Palettes — the only place colours are defined.
#
# §3.4 "calm map": the basemap is background, the markers are the message. Roads are
# three quiet tones (minor / main / motorway+trunk), never white in dark mode, and
# every name is plain text in one muted colour with a thin halo — no shields, no
# boxes, no icons behind text.
# -------------------------------------------------------------------------------------------

LIGHT = {
    "background":        "#F3F5F2",   # matches the app background (§3.1)
    "water":             "#C4D0D5",   # muted blue-grey
    "waterway":          "#BCC9CF",
    "waterLabel":        "#6A7F8A",
    "park":              "#E4ECE3",   # very pale green (§3.4)
    "wood":              "#DDE6DC",
    "residential":       "#EDEEEA",
    "building":          "#E7E8E3",
    "buildingOutline":   "#DCDED8",
    "aeroway":           "#EFEFEC",
    "path":              "#E6E7E3",
    "roadMinor":         "#FFFFFF",   # §3.4: white minor streets on the #F3F5F2 ground
    "roadMain":          "#E6EAE5",   # primary / secondary / tertiary
    "roadMotorway":      "#DDE3DC",   # motorway + trunk (e.g. Majka Tereza)
    "pier":              "#EDEEEA",
    "railway":           "#DEDFDA",
    "railwayDash":       "#F5F6F3",
    "boundary":          "#C3C8C1",
    "labelText":         "#6B766F",   # every street and place name
    "labelHalo":         "#F3F5F2",
}

DARK = {
    "background":        "#0F1411",   # app dark background (§3.1)
    "water":             "#16242A",
    "waterway":          "#1A2A31",
    "waterLabel":        "#6E8B96",
    "park":              "#16201A",
    "wood":              "#141D18",
    "residential":       "#131A16",
    "building":          "#19211C",
    "buildingOutline":   "#212B24",
    "aeroway":           "#151C18",
    "path":              "#1A211D",
    "roadMinor":         "#1E2521",   # §3.4 dark roads: never white or bright
    "roadMain":          "#26302A",
    "roadMotorway":      "#2C3630",
    "pier":              "#19211C",
    "railway":           "#222B25",
    "railwayDash":       "#161E19",
    "boundary":          "#2B342E",
    "labelText":         "#8A958E",   # every street and place name
    "labelHalo":         "#0F1411",
}

# -------------------------------------------------------------------------------------------
# Which palette entry drives which layer's paint property. Label layers are not listed:
# every text layer gets labelText / labelHalo (water names: waterLabel), see calm().
# -------------------------------------------------------------------------------------------

ROAD = {"line-color": "roadMinor"}
MAIN = {"line-color": "roadMain"}
MOTORWAY = {"line-color": "roadMotorway"}

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
    "aeroway-taxiway":               {"line-color": "aeroway"},
    "aeroway-runway-casing":         {"line-color": "aeroway"},
    "aeroway-area":                  {"fill-color": "aeroway"},
    "aeroway-runway":                MAIN,
    "road_area_pier":                {"fill-color": "pier"},
    "road_pier":                     {"line-color": "pier"},
    "highway_path":                  {"line-color": "path"},
    "highway_minor":                 ROAD,
    # Casings take the road's own colour: a road is one calm band, not a white line
    # with a dark outline.
    "highway_major_casing":          MAIN,
    "highway_major_inner":           MAIN,
    "highway_major_subtle":          MAIN,
    "highway_trunk_casing":          MOTORWAY,
    "highway_trunk_inner":           MOTORWAY,
    "highway_trunk_subtle":          MOTORWAY,
    "tunnel_motorway_casing":        MOTORWAY,
    "tunnel_motorway_inner":         MOTORWAY,
    "highway_motorway_casing":       MOTORWAY,
    "highway_motorway_inner":        MOTORWAY,
    "highway_motorway_subtle":       MOTORWAY,
    "highway_motorway_bridge_casing": MOTORWAY,
    "highway_motorway_bridge_inner": MOTORWAY,
    "railway_transit":               {"line-color": "railway"},
    "railway_transit_dashline":      {"line-color": "railwayDash"},
    "railway_service":               {"line-color": "railway"},
    "railway_service_dashline":      {"line-color": "railwayDash"},
    "railway":                       {"line-color": "railway"},
    "railway_dashline":              {"line-color": "railwayDash"},
    "boundary_3":                    {"line-color": "boundary"},
    "boundary_2":                    {"line-color": "boundary"},
    "boundary_disputed":             {"line-color": "boundary"},
}

# Road widths (dp). Motorway and trunk are only slightly wider than main roads — the
# hierarchy is carried by a small step in tone, not by a thick bright band.
MAIN_INNER = ["interpolate", ["exponential", 1.3], ["zoom"], 10, 1.4, 20, 18]
MAIN_CASING = ["interpolate", ["exponential", 1.3], ["zoom"], 10, 2.0, 20, 20]
MOTORWAY_INNER = ["interpolate", ["exponential", 1.3], ["zoom"], 6, 0.8, 10, 1.8, 20, 21]
MOTORWAY_CASING = ["interpolate", ["exponential", 1.3], ["zoom"], 6, 1.0, 10, 2.4, 20, 23]

WIDTHS = {
    "highway_major_inner": MAIN_INNER,
    "highway_major_casing": MAIN_CASING,
    "highway_trunk_inner": MOTORWAY_INNER,
    "highway_trunk_casing": MOTORWAY_CASING,
    "highway_motorway_inner": MOTORWAY_INNER,
    "highway_motorway_casing": MOTORWAY_CASING,
    "highway_motorway_bridge_inner": MOTORWAY_INNER,
    "highway_motorway_bridge_casing": MOTORWAY_CASING,
    "tunnel_motorway_inner": MOTORWAY_INNER,
    "tunnel_motorway_casing": MOTORWAY_CASING,
}

WATER_LABELS = {"waterway_line_label", "water_name_point_label", "water_name_line_label"}

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
        "glyphs": style["glyphs"],
        "layers": style["layers"],
    }

    style["layers"] = calm(split_trunk(style["layers"]))
    # Nothing draws a sprite icon any more (no shields, no place dots), so the
    # sprite sheet is not downloaded at all.
    style.pop("sprite", None)

    for layer in style["layers"]:
        layer_id = layer.get("id")
        mapping = LAYER_PAINT.get(layer_id) or label_paint(layer)

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


def split_trunk(layers: list[dict]) -> list[dict]:
    """
    Positron draws trunk roads with the primary/secondary/tertiary layers. §3.4 gives
    motorway AND trunk the same tone, so each major layer is split in two: the
    original keeps primary/secondary/tertiary, a copy right above it takes trunk.
    Plain colours per layer keep the palette a simple key → colour map.
    """
    out: list[dict] = []
    for layer in layers:
        layer_id = layer.get("id", "")
        if not layer_id.startswith("highway_major_"):
            out.append(layer)
            continue
        main = json.loads(json.dumps(layer))
        trunk = json.loads(json.dumps(layer))
        main["filter"] = replace_class_filter(main["filter"], ["primary", "secondary", "tertiary"])
        trunk["filter"] = replace_class_filter(trunk["filter"], ["trunk"])
        trunk["id"] = layer_id.replace("highway_major_", "highway_trunk_")
        out += [main, trunk]
    return out


def replace_class_filter(expression, classes: list[str]):
    """Swaps the class list inside positron's ["match", ["get", "class"], [...], true, false]."""
    if isinstance(expression, list):
        if (len(expression) == 5 and expression[0] == "match"
                and expression[1] == ["get", "class"] and isinstance(expression[2], list)):
            return ["match", ["get", "class"], classes, True, False]
        return [replace_class_filter(part, classes) for part in expression]
    return expression


def calm(layers: list[dict]) -> list[dict]:
    """
    §3.4 "streets and names must be calm":
      * no road shields or highway refs — every layer with an icon behind text goes;
      * no icon of any kind next to a name (place dots, airport pin) — plain text only;
      * one label colour, one thin halo, no blur;
      * road widths from WIDTHS.
    """
    out: list[dict] = []
    for layer in layers:
        layout = layer.get("layout", {})
        layer_id = layer.get("id", "")

        is_shield = (
            "shield" in layer_id
            or "icon-text-fit" in layout
            or ("icon-image" in layout and "ref" in json.dumps(layout.get("text-field", "")))
        )
        if is_shield:
            continue

        for key in [k for k in layout if k.startswith("icon-")]:
            del layout[key]

        paint = layer.setdefault("paint", {})
        if layer.get("type") == "symbol" and "text-field" in layout:
            paint.pop("text-halo-blur", None)
            paint["text-halo-width"] = 1

        if layer_id in WIDTHS:
            paint["line-width"] = WIDTHS[layer_id]

        out.append(layer)
    return out


def label_paint(layer: dict) -> dict | None:
    """Every name layer: one muted colour and a thin halo (§3.4)."""
    if layer.get("type") != "symbol" or "text-field" not in layer.get("layout", {}):
        return None
    colour = "waterLabel" if layer["id"] in WATER_LABELS else "labelText"
    return {"text-color": colour, "text-halo-color": "labelHalo"}


def colours_in(value) -> list[str]:
    """Every literal colour inside a paint value, including inside expressions."""
    found: list[str] = []
    if isinstance(value, str):
        if re.match(r"^(#|rgb|hsl|white$|black$)", value.strip().lower()):
            found.append(value)
    elif isinstance(value, list):
        for part in value:
            found += colours_in(part)
    return found


def luminance(colour: str) -> float:
    """Relative luminance of a #RRGGBB colour (WCAG); anything else counts as bright."""
    c = colour.strip().lower()
    if not re.match(r"^#[0-9a-f]{6}$", c):
        return 1.0
    def channel(v: int) -> float:
        s = v / 255
        return s / 12.92 if s <= 0.03928 else ((s + 0.055) / 1.055) ** 2.4
    r, g, b = (channel(int(c[i:i + 2], 16)) for i in (1, 3, 5))
    return 0.2126 * r + 0.7152 * g + 0.0722 * b


def validate(style: dict, palette: dict[str, str], dark: bool) -> list[str]:
    """
    The "check the whole city for white/bright layers" rule, as code:
      * every colour on the map comes from the palette (nothing untagged slips through);
      * no layer asks for a sprite icon;
      * in dark mode nothing is brighter than the label text — the brightest thing a
        dark basemap may show is a name, never a road.
    """
    problems: list[str] = []
    ceiling = luminance(palette["labelText"])
    for layer in style["layers"]:
        tagged = layer.get("metadata", {}).get("kanta:paint", {})
        for prop, value in layer.get("paint", {}).items():
            if "color" not in prop:
                continue
            if prop not in tagged:
                problems.append(f"{layer['id']}.{prop} is not driven by the palette")
            if dark:
                for colour in colours_in(value):
                    if luminance(colour) > ceiling + 1e-9:
                        problems.append(f"{layer['id']}.{prop} = {colour} is brighter than label text")
        if any(k.startswith("icon-") for k in layer.get("layout", {})):
            problems.append(f"{layer['id']} still draws an icon")
    return problems


def main() -> int:
    print("Kanta — map style generator")
    base = fetch_base()
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    for palette, filename, label in (
        (LIGHT, "kanta_light.json", "Kanta Light"),
        (DARK, "kanta_dark.json", "Kanta Dark"),
    ):
        style = build(base, palette, label)
        problems = validate(style, palette, dark=palette is DARK)
        if problems:
            print(f"  {filename}: {len(problems)} problem(s)")
            for problem in problems:
                print(f"    - {problem}")
            return 1
        path = OUT_DIR / filename
        path.write_text(json.dumps(style, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        size_kb = path.stat().st_size / 1024
        print(f"  wrote {path} ({size_kb:.0f} KB, {len(style['layers'])} layers, "
              f"{len(palette)} palette entries)")

    print("\nOpen either file in Maputnik to edit visually — see tools/map_style/MAPUTNIK.md")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
