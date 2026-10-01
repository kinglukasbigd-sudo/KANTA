"""
Kanta — Overture Maps source for the OSM importer (import_osm.py --source overture).

Overture Maps publishes OpenStreetMap's waste containers (base theme,
infrastructure type) and its administrative boundaries (divisions theme) as
GeoParquet in a public S3 bucket. Every row keeps its original OSM tags and
OSM id, so the importer can classify it exactly as it classifies Overpass
output.

Use it when Overpass is unreachable: it is blocked on some networks, and it is
overloaded at times. The data is usually a few weeks behind live OSM, because
Overture publishes monthly.

Needs pyarrow (`pip install pyarrow`). Only the parquet row groups whose
bounding-box statistics overlap Skopje are downloaded, a few MB in all.
"""

from __future__ import annotations

import os
import struct
from concurrent.futures import ThreadPoolExecutor

BUCKET = "overturemaps-us-west-2"
REGION = "us-west-2"

# Same box as the Overpass queries in import_osm.py: (south, west, north, east).
SOUTH, WEST, NORTH, EAST = 41.85, 21.05, 42.20, 21.85

OSM_TYPES = {"n": "node", "w": "way"}


# ---------------------------------------------------------------------------------------------
# S3 access
# ---------------------------------------------------------------------------------------------

def _filesystem():
    try:
        from pyarrow import fs
    except ImportError:
        raise SystemExit(
            "\n--source overture needs pyarrow:\n\n    pip install pyarrow\n"
        ) from None

    options = {"anonymous": True, "region": REGION}
    # The AWS SDK inside pyarrow ignores HTTPS_PROXY unless it is passed in.
    proxy = os.environ.get("HTTPS_PROXY") or os.environ.get("https_proxy")
    if proxy:
        options["proxy_options"] = proxy
    return fs.S3FileSystem(**options)


def latest_release(s3=None) -> str:
    """Newest release folder, e.g. '2026-09-23.1'."""
    from pyarrow import fs

    s3 = s3 or _filesystem()
    entries = s3.get_file_info(fs.FileSelector(f"{BUCKET}/release/"))
    releases = sorted(
        entry.base_name for entry in entries if entry.type == fs.FileType.Directory
    )
    if not releases:
        raise SystemExit("No Overture releases found in the public bucket.")
    return releases[-1]


def _read_box(s3, release: str, theme: str, kind: str, columns: list[str]) -> list[dict]:
    """Rows whose bbox overlaps Skopje's box, from every file of one Overture type."""
    from pyarrow import fs
    import pyarrow.parquet as pq

    folder = f"{BUCKET}/release/{release}/theme={theme}/type={kind}/"
    paths = [
        entry.path
        for entry in s3.get_file_info(fs.FileSelector(folder))
        if entry.path.endswith(".parquet")
    ]
    if not paths:
        raise SystemExit(f"Overture release {release} has no {theme}/{kind} files.")

    def overlaps(xmin: float, xmax: float, ymin: float, ymax: float) -> bool:
        return not (xmin > EAST or xmax < WEST or ymin > NORTH or ymax < SOUTH)

    def read(path: str) -> list[dict]:
        parquet = pq.ParquetFile(path, filesystem=s3)
        metadata = parquet.metadata
        names = [metadata.schema.column(i).path for i in range(metadata.num_columns)]
        rows: list[dict] = []

        for index in range(metadata.num_row_groups):
            group = metadata.row_group(index)
            stats = {
                name: group.column(names.index(name)).statistics
                for name in ("bbox.xmin", "bbox.xmax", "bbox.ymin", "bbox.ymax")
            }
            # Overture sorts files spatially, so the stats prune all but a few
            # row groups. A group without stats is read rather than guessed at.
            if all(s is not None and s.has_min_max for s in stats.values()) and not overlaps(
                stats["bbox.xmin"].min, stats["bbox.xmax"].max,
                stats["bbox.ymin"].min, stats["bbox.ymax"].max,
            ):
                continue

            for row in parquet.read_row_group(index, columns=columns + ["bbox"]).to_pylist():
                box = row["bbox"]
                if overlaps(box["xmin"], box["xmax"], box["ymin"], box["ymax"]):
                    rows.append(row)
        return rows

    with ThreadPoolExecutor(max_workers=8) as pool:
        return [row for rows in pool.map(read, paths) for row in rows]


# ---------------------------------------------------------------------------------------------
# WKB → coordinates (2D only, which is all Overture publishes)
# ---------------------------------------------------------------------------------------------

def parse_wkb(data: bytes):
    """Returns ('Point', (lon, lat)), ('Polygon', [ring, ...]) or
    ('MultiPolygon', [[ring, ...], ...]); lines and collections likewise."""

    def read(offset: int):
        order = "<" if data[offset] == 1 else ">"
        (geometry_type,) = struct.unpack_from(order + "I", data, offset + 1)
        offset += 5

        def points(at: int):
            (count,) = struct.unpack_from(order + "I", data, at)
            at += 4
            coords = [struct.unpack_from(order + "dd", data, at + 16 * i) for i in range(count)]
            return coords, at + 16 * count

        def rings(at: int):
            (count,) = struct.unpack_from(order + "I", data, at)
            at += 4
            result = []
            for _ in range(count):
                ring, at = points(at)
                result.append(ring)
            return result, at

        if geometry_type == 1:
            return ("Point", struct.unpack_from(order + "dd", data, offset)), offset + 16
        if geometry_type == 2:
            line, offset = points(offset)
            return ("LineString", line), offset
        if geometry_type == 3:
            polygon, offset = rings(offset)
            return ("Polygon", polygon), offset
        if geometry_type in (4, 5, 6, 7):
            (count,) = struct.unpack_from(order + "I", data, offset)
            offset += 4
            parts = []
            for _ in range(count):
                part, offset = read(offset)
                parts.append(part[1] if geometry_type != 7 else part)
            name = {4: "MultiPoint", 5: "MultiLineString", 6: "MultiPolygon", 7: "GeometryCollection"}
            return (name[geometry_type], parts), offset
        raise ValueError(f"unsupported WKB geometry type {geometry_type}")

    return read(0)[0]


def _all_points(kind: str, coords) -> list[tuple[float, float]]:
    if kind == "Point":
        return [coords]
    if kind in ("LineString", "MultiPoint"):
        return list(coords)
    if kind in ("Polygon", "MultiLineString"):
        return [p for ring in coords for p in ring]
    if kind == "MultiPolygon":
        return [p for polygon in coords for ring in polygon for p in ring]
    return [p for part in coords for p in _all_points(*part)]


def _osm_record(sources) -> tuple[str, int] | None:
    """('node' | 'way', id) from Overture's sources, e.g. record_id 'n8640106334@2'."""
    for source in sources or []:
        if source.get("dataset") != "OpenStreetMap":
            continue
        record = source.get("record_id") or ""
        osm_type = OSM_TYPES.get(record[:1])
        if osm_type:
            return osm_type, int(record[1:].split("@")[0])
    return None


# ---------------------------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------------------------

def fetch_elements(release: str) -> list[dict]:
    """Overpass-shaped elements, {type, id, lat, lon, tags}, for every waste feature
    and every feature tagged bin=yes in Skopje's box. Ways come back as the
    centre of their bounding box, the same point Overpass's `out center` gives."""
    s3 = _filesystem()
    rows = _read_box(s3, release, "base", "infrastructure",
                     ["geometry", "sources", "source_tags", "subtype"])

    elements: list[dict] = []
    for row in rows:
        tags = dict(row["source_tags"] or [])
        if row["subtype"] != "waste_management" and tags.get("bin") != "yes":
            continue
        record = _osm_record(row["sources"])
        if record is None:
            continue

        kind, coords = parse_wkb(row["geometry"])
        if kind == "Point":
            lon, lat = coords
        else:
            points = _all_points(kind, coords)
            lons = [p[0] for p in points]
            lats = [p[1] for p in points]
            lon, lat = (min(lons) + max(lons)) / 2, (min(lats) + max(lats)) / 2

        elements.append({"type": record[0], "id": record[1], "lat": lat, "lon": lon, "tags": tags})
    return elements


def fetch_boundaries(release: str) -> list[dict]:
    """North Macedonian municipalities overlapping Skopje's box, as
    {tags, outers, inners}. Overture's 'county' is OSM's admin_level=7,
    the level Skopje's ten municipalities use."""
    s3 = _filesystem()
    rows = _read_box(s3, release, "divisions", "division_area",
                     ["geometry", "sources", "names", "country", "subtype", "class"])

    boundaries: list[dict] = []
    for row in rows:
        if row["country"] != "MK" or row["subtype"] != "county" or row["class"] != "land":
            continue

        names = row["names"] or {}
        tags = {"name": names.get("primary")}
        for language, value in names.get("common") or []:
            tags[f"name:{language}"] = value

        kind, coords = parse_wkb(row["geometry"])
        polygons = [coords] if kind == "Polygon" else coords if kind == "MultiPolygon" else []
        boundaries.append({
            "tags": tags,
            "outers": [polygon[0] for polygon in polygons],
            "inners": [ring for polygon in polygons for ring in polygon[1:]],
        })
    return boundaries
