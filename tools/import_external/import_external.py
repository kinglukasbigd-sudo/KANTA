#!/usr/bin/env python3
"""
Kanta — containers from outside OpenStreetMap.

Two sources, each written as SQL you paste into the Supabase SQL editor after
migration 0018 and seed/containers.sql:

    # Trash cans Mapillary detected in street-level photos (free token from
    # https://www.mapillary.com/dashboard/developers)
    MAPILLARY_TOKEN=MLY|... python3 tools/import_external/import_external.py mapillary

    # A list of locations from an operator: CSV, GeoJSON or KML
    # (Google My Maps exports KML)
    python3 tools/import_external/import_external.py list pakomak.kml --name pakomak

Everything imported starts UNVERIFIED (dashed marker, §4.6) unless --verified is
passed for a list you trust. Neighbours confirm it with "Yes, it's here", and
two "missing" reports remove it, exactly like a container added in the app.

Standard library only, except `mapillary`, which needs mapbox-vector-tile. See tools/import_external/README.md.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path

# Reuse the polygon code from the OSM importer rather than copying it.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "import_osm"))
from import_osm import Boundary  # noqa: E402

SEED_SQL = Path("supabase/seed/containers.sql")
MAPILLARY_TILES = "https://tiles.mapillary.com/maps/vtp/mly_map_feature_point/2/{z}/{x}/{y}"
MAPILLARY_ZOOM = 14
# Two detections closer than this are taken to be the same can.
MAPILLARY_SELF_M = 3.0

# Mapillary places a detection from several photos, so it can be several metres
# off. Inside this radius of an existing container of the same kind, it is
# treated as that container.
MAPILLARY_DEDUPE_M = 10.0
# An operator's list is surveyed; the §4.6 duplicate radii apply.
LIST_DEDUPE_M = {"big": 10.0, "small": 5.0}

KINDS = ("big", "small")
CATEGORIES = ("general", "glass", "paper", "plastic", "mixed_recycling")


@dataclass
class Row:
    external_id: str
    kind: str
    category: str
    lon: float
    lat: float
    municipality_id: int | None = None


# ---------------------------------------------------------------------------------------------
# Municipality boundaries, read back from the OSM seed so this tool needs no network for them
# ---------------------------------------------------------------------------------------------

def load_boundaries(seed: Path) -> list[Boundary]:
    if not seed.exists():
        raise SystemExit(f"{seed} not found. Run tools/import_osm/import_osm.py first.")
    text = seed.read_text(encoding="utf-8")
    boundaries = []
    pattern = r"-- (\d+): (.+)\nupdate municipalities set geom = st_geomfromtext\('MULTIPOLYGON\((.+?)\)', 4326\)"
    for match in re.finditer(pattern, text):
        outers, inners = [], []
        # Each polygon is "((outer), (inner), …)".
        for polygon in re.findall(r"\(\((.+?)\)\)", match.group(3)):
            rings = [
                [tuple(float(v) for v in point.split()) for point in ring.split(", ")]
                for ring in polygon.split("), (")
            ]
            outers.append(rings[0])
            inners.extend(rings[1:])
        boundaries.append(Boundary(int(match.group(1)), match.group(2), outers, inners))
    if len(boundaries) != 10:
        raise SystemExit(f"Expected 10 municipality boundaries in {seed}, found {len(boundaries)}.")
    return boundaries


def assign(rows: list[Row], boundaries: list[Boundary]) -> tuple[list[Row], int]:
    """Keep rows inside the ten municipalities; return (kept, outside count)."""
    kept = []
    for row in rows:
        for boundary in boundaries:
            if boundary.contains(row.lon, row.lat):
                row.municipality_id = boundary.municipality_id
                kept.append(row)
                break
    return kept, len(rows) - len(kept)


# ---------------------------------------------------------------------------------------------
# Mapillary
#
# Read from Mapillary's map-feature vector tiles. Its search API (graph.mapillary.com
# /map_features) accepts the token but returns no data for any area, so tiles are the
# route that works. Each zoom-14 tile is about 2.4 × 1.8 km over Skopje.
# ---------------------------------------------------------------------------------------------

def _tile_range(west: float, south: float, east: float, north: float, zoom: int):
    n = 2 ** zoom

    def tile_x(lon: float) -> int:
        return int((lon + 180) / 360 * n)

    def tile_y(lat: float) -> int:
        return int((1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n)

    return [
        (x, y)
        for x in range(tile_x(west), tile_x(east) + 1)
        for y in range(tile_y(north), tile_y(south) + 1)
    ]


def mapillary_tile(token: str, x: int, y: int, cache: Path | None) -> bytes:
    """One zoom-14 map-feature tile, as raw Mapbox Vector Tile bytes."""
    path = cache / f"mapillary-{MAPILLARY_ZOOM}-{x}-{y}.pbf" if cache else None
    if path and path.exists():
        return path.read_bytes()

    url = MAPILLARY_TILES.format(z=MAPILLARY_ZOOM, x=x, y=y) + "?" + urllib.parse.urlencode(
        {"access_token": token})
    for attempt in range(1, 4):
        try:
            with urllib.request.urlopen(url, timeout=120) as response:
                data = response.read()
            break
        except urllib.error.HTTPError as error:
            if error.code in (401, 403):
                raise SystemExit("Mapillary refused the token (HTTP %d). Check MAPILLARY_TOKEN." % error.code)
            if error.code == 404:  # no imagery here at all
                data = b""
                break
            if attempt == 3:
                raise
            time.sleep(10 * attempt)
        except urllib.error.URLError:
            if attempt == 3:
                raise
            time.sleep(10 * attempt)

    if path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    return data


def fetch_mapillary(token: str, boundaries: list[Boundary], cache: Path | None,
                    seen_since: int | None = None) -> list[Row]:
    try:
        import mapbox_vector_tile
    except ImportError:
        raise SystemExit("\nThe mapillary command needs:  pip install mapbox-vector-tile\n") from None

    lons = [p[0] for b in boundaries for ring in b.outers for p in ring]
    lats = [p[1] for b in boundaries for ring in b.outers for p in ring]
    tiles = _tile_range(min(lons), min(lats), max(lons), max(lats), MAPILLARY_ZOOM)
    n = 2 ** MAPILLARY_ZOOM

    features: dict[str, tuple[float, float]] = {}
    for done, (x, y) in enumerate(tiles, start=1):
        data = mapillary_tile(token, x, y, cache)
        layers = mapbox_vector_tile.decode(data, default_options={"y_coord_down": True}) if data else {}
        for layer in layers.values():
            extent = layer.get("extent", 4096)
            for feature in layer["features"]:
                props = feature["properties"]
                if props.get("value") != "object--trash-can":
                    continue
                # Most of Skopje's imagery is from 2019; an old sighting may be gone.
                if seen_since and time.gmtime(props.get("last_seen_at", 0) / 1000).tm_year < seen_since:
                    continue
                px, py = feature["geometry"]["coordinates"]
                lon = (x + px / extent) / n * 360 - 180
                lat = math.degrees(math.atan(math.sinh(math.pi * (1 - 2 * (y + py / extent) / n))))
                features[str(props["id"])] = (lon, lat)
        print(f"\r  {done}/{len(tiles)} tiles, {len(features)} trash cans so far", end="", flush=True)
    print()

    # One can is sometimes detected twice from different photo sequences.
    rows: list[Row] = []
    for feature_id, (lon, lat) in sorted(features.items()):
        row = Row(feature_id, "small", "general", lon, lat)
        if any(_distance_m(row, other) <= MAPILLARY_SELF_M for other in rows):
            continue
        rows.append(row)
    return rows


def _distance_m(a: Row, b: Row) -> float:
    dlat = math.radians(b.lat - a.lat)
    dlon = math.radians(b.lon - a.lon) * math.cos(math.radians(a.lat))
    return 6_371_000 * math.hypot(dlat, dlon)


# ---------------------------------------------------------------------------------------------
# Lists: CSV, GeoJSON, KML
# ---------------------------------------------------------------------------------------------

LAT_NAMES = ("lat", "latitude", "y", "geo_lat", "ширина")
LON_NAMES = ("lon", "lng", "long", "longitude", "x", "geo_lon", "должина")


def read_list(path: Path, default_kind: str, default_category: str) -> list[Row]:
    suffix = path.suffix.lower()
    if suffix == ".csv":
        records = _read_csv(path)
    elif suffix in (".geojson", ".json"):
        records = _read_geojson(path)
    elif suffix == ".kml":
        records = _read_kml(path)
    else:
        raise SystemExit(f"Don't know how to read {suffix} files. Use .csv, .geojson or .kml.")

    rows = []
    for index, (lon, lat, props) in enumerate(records, start=1):
        kind = (props.get("kind") or default_kind).strip().lower()
        category = (props.get("category") or default_category).strip().lower()
        if kind not in KINDS:
            raise SystemExit(f"Row {index}: kind must be one of {KINDS}, got {kind!r}")
        if category not in CATEGORIES:
            raise SystemExit(f"Row {index}: category must be one of {CATEGORIES}, got {category!r}")
        external_id = str(props.get("id") or f"{lat:.6f},{lon:.6f}")
        rows.append(Row(external_id, kind, category, lon, lat))
    return rows


def _read_csv(path: Path):
    with path.open(newline="", encoding="utf-8-sig") as handle:
        reader = csv.DictReader(handle)
        fields = {name.strip().lower(): name for name in reader.fieldnames or []}
        lat_key = next((fields[n] for n in LAT_NAMES if n in fields), None)
        lon_key = next((fields[n] for n in LON_NAMES if n in fields), None)
        if not lat_key or not lon_key:
            raise SystemExit(f"{path}: needs latitude and longitude columns, found {list(fields)}")
        for record in reader:
            props = {k.strip().lower(): v for k, v in record.items() if k}
            yield float(record[lon_key].replace(",", ".")), float(record[lat_key].replace(",", ".")), props


def _read_geojson(path: Path):
    data = json.loads(path.read_text(encoding="utf-8"))
    for feature in data.get("features", []):
        geometry = feature.get("geometry") or {}
        if geometry.get("type") != "Point":
            continue
        lon, lat = geometry["coordinates"][:2]
        props = {k.lower(): v for k, v in (feature.get("properties") or {}).items()}
        if feature.get("id") is not None and "id" not in props:
            props["id"] = feature["id"]
        yield float(lon), float(lat), props


def _read_kml(path: Path):
    root = ElementTree.parse(path).getroot()
    namespace = root.tag.split("}")[0] + "}" if root.tag.startswith("{") else ""
    for index, placemark in enumerate(root.iter(f"{namespace}Placemark"), start=1):
        coordinates = placemark.find(f".//{namespace}Point/{namespace}coordinates")
        if coordinates is None or not coordinates.text:
            continue
        lon, lat = (float(v) for v in coordinates.text.strip().split(",")[:2])
        props = {}
        for data in placemark.iter(f"{namespace}Data"):
            value = data.find(f"{namespace}value")
            props[data.get("name", "").lower()] = value.text if value is not None else ""
        props.setdefault("id", placemark.get("id"))
        yield lon, lat, props


# ---------------------------------------------------------------------------------------------
# SQL
# ---------------------------------------------------------------------------------------------

def sql_text(value: str) -> str:
    return "'" + value.replace("'", "''") + "'"


def write_sql(rows: list[Row], path: Path, source: str, verified: bool, title: str,
              dedupe_m) -> None:
    out = [
        "-- =============================================================================",
        f"-- Kanta — {title}",
        "--",
        "-- GENERATED FILE — do not edit by hand. See tools/import_external/README.md.",
        f"-- Rows: {len(rows)}   source = '{source}'   verified = {str(verified).lower()}",
        "--",
        "-- Run after migration 0018 and seed/containers.sql. Safe to re-run: rows are",
        "-- matched on (source, external_id), and a row is skipped when a container of",
        "-- the same kind is already close by, whatever its source.",
        "-- =============================================================================",
        "set search_path to public, extensions;",
        "",
        "begin;",
        "",
        "select sync_container_code_seq();",
        "",
        "create temp table external_import (",
        "    ordinal     int,",
        "    external_id text,",
        "    kind        text,",
        "    category    text,",
        "    lon         double precision,",
        "    lat         double precision,",
        "    dedupe_m    double precision",
        ") on commit drop;",
        "",
    ]
    for start in range(0, len(rows), 500):
        chunk = rows[start:start + 500]
        out.append("insert into external_import (ordinal, external_id, kind, category, lon, lat, dedupe_m) values")
        out.append(",\n".join(
            f"    ({start + i + 1}, {sql_text(r.external_id)}, '{r.kind}', '{r.category}', "
            f"{r.lon:.7f}, {r.lat:.7f}, {dedupe_m(r):.0f})"
            for i, r in enumerate(chunk)
        ) + ";")
        out.append("")

    out += [
        "insert into containers (code, kind, category, geom, source, external_id,",
        "                        verified, status, status_since, created_at)",
        "select",
        "    next_container_code(),",
        "    e.kind,",
        "    e.category,",
        "    st_setsrid(st_makepoint(e.lon, e.lat), 4326)::geography,",
        f"    '{source}',",
        "    e.external_id,",
        f"    {str(verified).lower()},",
        "    'ok',",
        "    now(),",
        "    now()",
        "from external_import e",
        "where not exists (",
        f"    select 1 from containers c where c.source = '{source}' and c.external_id = e.external_id",
        ")",
        "and not exists (",
        "    select 1 from containers c",
        "     where c.deleted_at is null",
        # Migration 0020: a bin of unknown size nearby could be either kind.
        "       and c.kind in (e.kind, 'unknown')",
        "       and st_dwithin(c.geom, st_setsrid(st_makepoint(e.lon, e.lat), 4326)::geography,",
        "                      e.dedupe_m)",
        ")",
        "order by e.ordinal;",
        "",
        "update containers c",
        "   set municipality_id = m.id",
        "  from municipalities m",
        " where m.geom is not null",
        f"   and c.source = '{source}'",
        "   and c.deleted_at is null",
        "   and st_contains(m.geom::geometry, c.geom::geometry)",
        "   and c.municipality_id is distinct from m.id;",
        "",
        "commit;",
        "",
        "select coalesce(m.name_en, '(unassigned)') as municipality, count(*) as containers",
        "from containers c",
        "left join municipalities m on m.id = c.municipality_id",
        f"where c.source = '{source}' and c.deleted_at is null",
        "group by m.name_en",
        "order by containers desc;",
    ]
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(out) + "\n", encoding="utf-8")


def summary(rows: list[Row], boundaries: list[Boundary]) -> None:
    names = {b.municipality_id: b.name for b in boundaries}
    counts: dict[str, int] = {}
    for row in rows:
        counts[names[row.municipality_id]] = counts.get(names[row.municipality_id], 0) + 1
    for name, count in sorted(counts.items(), key=lambda item: -item[1]):
        print(f"  {name:<26}{count:>6}")
    print(f"  {'TOTAL':<26}{len(rows):>6}")


# ---------------------------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(description="Import Skopje containers from outside OSM.")
    parser.add_argument("--seed", type=Path, default=SEED_SQL,
                        help="OSM seed to read municipality boundaries from")
    commands = parser.add_subparsers(dest="command", required=True)

    mapillary = commands.add_parser("mapillary", help="trash cans detected by Mapillary")
    mapillary.add_argument("--token", default=os.environ.get("MAPILLARY_TOKEN"),
                           help="Mapillary client token (default: $MAPILLARY_TOKEN)")
    mapillary.add_argument("--out", type=Path, default=Path("supabase/seed/mapillary.sql"))
    mapillary.add_argument("--cache-dir", type=Path, default=Path("tools/import_external/.cache"))
    mapillary.add_argument("--no-cache", action="store_true")
    mapillary.add_argument("--seen-since", type=int, default=None, metavar="YEAR",
                           help="only cans last seen in this year or later (default: all)")

    listing = commands.add_parser("list", help="an operator's list: .csv, .geojson or .kml")
    listing.add_argument("file", type=Path)
    listing.add_argument("--name", required=True,
                         help="short name for the list, e.g. pakomak; names the output file")
    listing.add_argument("--kind", choices=KINDS, default="big",
                         help="kind for rows without a kind column (default: big)")
    listing.add_argument("--category", choices=CATEGORIES, default="general",
                         help="category for rows without a category column (default: general)")
    listing.add_argument("--verified", action="store_true",
                         help="import as verified (only for a list you trust, e.g. from the operator)")
    listing.add_argument("--out", type=Path, default=None)

    arguments = parser.parse_args()
    boundaries = load_boundaries(arguments.seed)

    if arguments.command == "mapillary":
        if not arguments.token:
            raise SystemExit("Needs a Mapillary token: --token MLY|… or MAPILLARY_TOKEN.\n"
                             "Get one free at https://www.mapillary.com/dashboard/developers")
        print("Mapillary trash-can detections")
        cache = None if arguments.no_cache else arguments.cache_dir
        rows = fetch_mapillary(arguments.token, boundaries, cache, arguments.seen_since)
        rows, outside = assign(rows, boundaries)
        write_sql(rows, arguments.out, "mapillary", False,
                  "trash cans detected by Mapillary (check Mapillary's terms before release)",
                  lambda r: MAPILLARY_DEDUPE_M)
        out = arguments.out
    else:
        print(f"List: {arguments.file}")
        rows = read_list(arguments.file, arguments.kind, arguments.category)
        rows, outside = assign(rows, boundaries)
        out = arguments.out or Path(f"supabase/seed/list_{arguments.name}.sql")
        write_sql(rows, out, "city", arguments.verified,
                  f"containers from the {arguments.name} list ({arguments.file.name})",
                  lambda r: LIST_DEDUPE_M[r.kind])

    if outside:
        print(f"  {outside} outside the ten municipalities, left out")
    summary(rows, boundaries)
    print(f"\nWrote {out}. Run it in the Supabase SQL editor after migration 0018.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
