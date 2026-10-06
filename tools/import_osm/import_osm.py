#!/usr/bin/env python3
"""
Kanta — OpenStreetMap seed importer (KANTA_SPEC.md §7).

Pulls Skopje's existing waste containers and municipality boundaries from
OpenStreetMap via the Overpass API and writes them out as SQL you paste into the
Supabase SQL editor.

Standard library only — nothing to pip install.

    python3 tools/import_osm/import_osm.py

When Overpass is unreachable, the same OSM data can come from Overture Maps'
monthly release instead (needs `pip install pyarrow`):

    python3 tools/import_osm/import_osm.py --source overture

See tools/import_osm/README.md for the full instructions.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

OVERPASS_URL = "https://overpass-api.de/api/interpreter"

# Wider than the original §7 query, to find every bin OSM knows about:
#   - ways as well as nodes: some containers are mapped as small areas
#   - recycling without recycling_type: classify() drops only the "centre" kind
#   - bin=yes, which mappers put on bus stops that have a bin
# The box is the same one as BOUNDARIES_QUERY. Points outside the ten
# municipalities are dropped in Python once the boundaries are assembled.
# `out center;` gives ways a single point: the centre of their bounding box.
CONTAINERS_QUERY = """
[out:json][timeout:180];
(
  nw["amenity"~"^(waste_disposal|waste_basket|recycling)$"](41.85,21.05,42.20,21.85);
  nw["bin"="yes"](41.85,21.05,42.20,21.85);
);
out center;
"""

# §7 first said "admin_level=8 relations inside Skopje". That is NOT how OSM actually
# tags Skopje: the ten municipalities are admin_level=7 ("Општина Карпош" /
# "Municipality of Karposh"), admin_level=8 is the City of Skopje itself, and
# admin_level=9 holds same-named settlement relations. Querying 8 returns one
# relation — the city — and no municipalities at all.
#
# So this asks for 7 and 8 and keeps whatever matches our ten names. A bounding
# box is used rather than `(area.a)` because the municipalities are a LEVEL ABOVE
# the city area in this tagging, so area-containment silently excludes them.
#
# `out geom;` returns each member way's coordinates, which is what lets us
# rebuild the polygons below without a GIS library.
BOUNDARIES_QUERY = """
[out:json][timeout:180];
relation["boundary"="administrative"]["admin_level"~"^(7|8)$"](41.85,21.05,42.20,21.85);
out geom;
"""

# The ids in supabase/seed/0001_municipalities.sql. OSM spells these several
# ways, so every plausible spelling is listed and matching is accent-insensitive.
MUNICIPALITIES = {
    1:  ["Центар", "Centar", "Qendër", "Qender"],
    2:  ["Карпош", "Karpoš", "Karpos", "Karposh"],
    3:  ["Аеродром", "Aerodrom"],
    4:  ["Гази Баба", "Gazi Baba"],
    5:  ["Кисела Вода", "Kisela Voda", "Kisella Vodë", "Kisella Vode", "Kisela Voda"],
    6:  ["Чаир", "Čair", "Cair", "Çair", "Chair"],
    7:  ["Бутел", "Butel"],
    8:  ["Ѓорче Петров", "Gjorče Petrov", "Gjorce Petrov", "Gjorçe Petrov", "Ǵorče Petrov",
         "Gjorche Petrov", "Djorce Petrov"],
    9:  ["Сарај", "Saraj"],
    10: ["Шуто Оризари", "Šuto Orizari", "Suto Orizari", "Shuto Orizare", "Shuto Orizari",
         "Shutka"],
}

# OSM prefixes the municipality relations; these are stripped before matching so
# "Municipality of Karposh" and "Општина Карпош" both resolve to id 2.
NAME_PREFIXES = (
    "municipality of ", "opstina ", "opština ", "општина ", "komuna e ", "komuna ",
    "grad ", "град ", "city of ",
)

OUTPUT_SQL = Path("supabase/seed/containers.sql")

# A bus stop tagged bin=yes says a bin is there, not exactly where. Its point
# is the stop's, so it is dropped if a mapped can is already this close…
BIN_TAG_MERGE_M = 20.0
# …or if another bin=yes point is (a stop drawn as both a node and a platform).
BIN_TAG_SELF_M = 10.0

# Against containers people added in the app, an import row is skipped inside
# the same radius add_container() uses for duplicates (§4.6): 10 m big, 5 m
# small. bin=yes points use BIN_TAG_MERGE_M, because their position is the stop's.
DUPLICATE_M = {"big": 10.0, "small": 5.0}


# ---------------------------------------------------------------------------------------------
# Overpass
# ---------------------------------------------------------------------------------------------

def overpass(query: str, cache: Path | None, label: str, url: str = OVERPASS_URL,
             retries: int = 3) -> dict:
    """POST a query to Overpass, with on-disk caching and polite retries.

    Overpass is a free, shared, donation-funded service. It returns 429 or 504
    when busy, and hammering it is how an IP gets blocked — so failures back off
    rather than retry immediately, and a cached response is reused by default.
    """
    if cache and cache.exists():
        print(f"  using cached {label}: {cache}")
        return json.loads(cache.read_text())

    data = query.strip().encode("utf-8")
    last_error: Exception | None = None

    for attempt in range(1, retries + 1):
        try:
            print(f"  requesting {label} from Overpass (attempt {attempt}/{retries})…")
            request = urllib.request.Request(
                url,
                data=data,
                headers={
                    # Overpass asks for a identifiable agent so it can contact
                    # you instead of silently blocking.
                    "User-Agent": "kanta-import/1.0 (+https://github.com/kanta-mk)",
                    "Content-Type": "text/plain; charset=utf-8",
                },
            )
            with urllib.request.urlopen(request, timeout=300) as response:
                payload = json.loads(response.read().decode("utf-8"))

            if cache:
                cache.parent.mkdir(parents=True, exist_ok=True)
                cache.write_text(json.dumps(payload))
            return payload

        except urllib.error.HTTPError as error:
            last_error = error
            if error.code in (429, 504):
                wait = 15 * attempt
                print(f"  Overpass is busy (HTTP {error.code}); waiting {wait}s…")
                time.sleep(wait)
            else:
                break
        except Exception as error:  # noqa: BLE001 — surfaced to the user below
            last_error = error
            time.sleep(5 * attempt)

    raise SystemExit(
        f"\nCould not reach Overpass for {label}: {last_error}\n"
        "It is a free shared service and is sometimes overloaded. Wait a few\n"
        "minutes and run again, or try --overpass-url https://overpass.kumi.systems/api/interpreter"
    )


# ---------------------------------------------------------------------------------------------
# Tag mapping (§7)
# ---------------------------------------------------------------------------------------------

@dataclass
class Container:
    osm_id: int        # nodes as-is, ways negated: the two id spaces overlap in OSM
    kind: str          # 'big' | 'small'
    category: str      # 'general' | 'glass' | 'paper' | 'plastic' | 'mixed_recycling'
    lon: float
    lat: float
    from_bin_tag: bool = False   # a bus stop's bin=yes rather than a mapped bin
    municipality_id: int | None = None


# OSM splits materials finely; Kanta has three. A Pakomak glass bank is usually
# tagged recycling:glass_bottles only, and is still a glass container.
MATERIALS = {
    "glass": ("glass", "glass_bottles"),
    "paper": ("paper", "cardboard", "paper_packaging", "newspaper", "magazines"),
    "plastic": ("plastic", "plastic_bottles", "plastic_packaging"),
}


def is_dumping_spot(tags: dict) -> bool:
    """amenity=waste_disposal + informal=yes marks a place where people dump
    rubbish without a container. Not a container, so never imported."""
    return tags.get("amenity") == "waste_disposal" and tags.get("informal") == "yes"


def classify(tags: dict) -> tuple[str, str] | None:
    """Map OSM tags to (kind, category) per §7. None means "not ours"."""
    amenity = tags.get("amenity")

    if amenity == "waste_disposal":
        if is_dumping_spot(tags):
            return None
        return "big", "general"

    if amenity == "waste_basket":
        return "small", "general"

    if amenity == "recycling":
        # A recycling centre is a yard you drive to, not a container on the street.
        # Untagged recycling_type is nearly always a container, so it is kept.
        if tags.get("recycling_type") == "centre":
            return None

        materials = [
            name
            for name, suffixes in MATERIALS.items()
            if any(tags.get(f"recycling:{suffix}") in ("yes", "only") for suffix in suffixes)
        ]

        if len(materials) == 1:
            return "big", materials[0]
        # Several materials in one unit, or none tagged: the spec's catch-all.
        return "big", "mixed_recycling"

    # A bus stop (or bench, platform, …) that has a bin, per its bin=yes tag.
    if tags.get("bin") == "yes":
        return "small", "general"

    return None


def to_container(element: dict) -> Container | None:
    """One Overpass element (or the Overture equivalent) → Container, or None."""
    if element.get("type") not in ("node", "way"):
        return None
    tags = element.get("tags", {})
    mapping = classify(tags)
    if mapping is None:
        return None

    # Overpass puts a way's `out center` point under "center".
    point = element.get("center") or element
    if "lat" not in point or "lon" not in point:
        return None

    kind, category = mapping
    return Container(
        osm_id=element["id"] if element["type"] == "node" else -element["id"],
        kind=kind,
        category=category,
        lon=point["lon"],
        lat=point["lat"],
        from_bin_tag=tags.get("amenity") not in ("waste_disposal", "waste_basket", "recycling"),
    )


def distance_m(a: Container, b: Container) -> float:
    """Haversine. Plenty accurate at the tens-of-metres scale it is used for."""
    lat1, lat2 = math.radians(a.lat), math.radians(b.lat)
    dlat = lat2 - lat1
    dlon = math.radians(b.lon - a.lon)
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * 6_371_000 * math.asin(math.sqrt(h))


def drop_bin_tag_duplicates(containers: list[Container]) -> tuple[list[Container], int]:
    """Drop bin=yes points that a mapped can, or another bin=yes point, already covers."""
    mapped_cans = [c for c in containers if c.kind == "small" and not c.from_bin_tag]
    kept: list[Container] = []
    kept_bin_tags: list[Container] = []
    dropped = 0

    for container in containers:
        if container.from_bin_tag and (
            any(distance_m(container, can) <= BIN_TAG_MERGE_M for can in mapped_cans)
            or any(distance_m(container, other) <= BIN_TAG_SELF_M for other in kept_bin_tags)
        ):
            dropped += 1
            continue
        kept.append(container)
        if container.from_bin_tag:
            kept_bin_tags.append(container)
    return kept, dropped


# ---------------------------------------------------------------------------------------------
# Boundary assembly
#
# Overpass hands back a relation as a bag of member ways. Turning those into
# polygons means stitching ways into closed rings, then pairing inner rings with
# the outer ring that contains them. Doing it here keeps the script dependency
# free — shapely/osmium would each be a pip install.
# ---------------------------------------------------------------------------------------------

Ring = list[tuple[float, float]]


def stitch_rings(ways: list[Ring]) -> list[Ring]:
    """Join open ways end-to-end until they close into rings."""
    remaining = [list(way) for way in ways if len(way) >= 2]
    rings: list[Ring] = []

    while remaining:
        current = remaining.pop(0)

        # Keep attaching any way that continues this one, from either end.
        progressed = True
        while progressed and current[0] != current[-1]:
            progressed = False
            for index, candidate in enumerate(remaining):
                if candidate[0] == current[-1]:
                    current.extend(candidate[1:])
                elif candidate[-1] == current[-1]:
                    current.extend(reversed(candidate[:-1]))
                elif candidate[-1] == current[0]:
                    current = candidate[:-1] + current
                elif candidate[0] == current[0]:
                    current = list(reversed(candidate[1:])) + current
                else:
                    continue
                remaining.pop(index)
                progressed = True
                break

        if current[0] == current[-1] and len(current) >= 4:
            rings.append(current)
        # An unclosed fragment means the relation is broken upstream in OSM.
        # Dropping it loses a sliver of boundary rather than the municipality.

    return rings


def ring_area(ring: Ring) -> float:
    """Shoelace area. Only the magnitude and sign matter here, not the units."""
    total = 0.0
    for i in range(len(ring) - 1):
        x1, y1 = ring[i]
        x2, y2 = ring[i + 1]
        total += x1 * y2 - x2 * y1
    return total / 2.0


def point_in_ring(lon: float, lat: float, ring: Ring) -> bool:
    """Ray casting. Used for the per-municipality summary only — the database
    does the authoritative assignment with ST_Contains (§7)."""
    inside = False
    for i in range(len(ring) - 1):
        x1, y1 = ring[i]
        x2, y2 = ring[i + 1]
        if (y1 > lat) != (y2 > lat):
            x_at = x1 + (lat - y1) / (y2 - y1) * (x2 - x1)
            if x_at > lon:
                inside = not inside
    return inside


@dataclass
class Boundary:
    municipality_id: int
    name: str
    outers: list[Ring] = field(default_factory=list)
    inners: list[Ring] = field(default_factory=list)

    def contains(self, lon: float, lat: float) -> bool:
        if not any(point_in_ring(lon, lat, ring) for ring in self.outers):
            return False
        return not any(point_in_ring(lon, lat, ring) for ring in self.inners)

    def to_wkt(self) -> str:
        """MULTIPOLYGON WKT. Every inner ring is attached to the first outer ring
        that contains it; holes that match no outer ring are dropped."""
        polygons: list[list[Ring]] = [[outer] for outer in self.outers]

        for inner in self.inners:
            probe = inner[0]
            for polygon in polygons:
                if point_in_ring(probe[0], probe[1], polygon[0]):
                    polygon.append(inner)
                    break

        def ring_text(ring: Ring) -> str:
            return "(" + ", ".join(f"{lon:.7f} {lat:.7f}" for lon, lat in ring) + ")"

        body = ", ".join(
            "(" + ", ".join(ring_text(ring) for ring in polygon) + ")"
            for polygon in polygons
        )
        return f"MULTIPOLYGON({body})"


def normalise(name: str) -> str:
    """Fold accents and case so 'Ǵorče Petrov' matches 'Gjorce Petrov'."""
    import unicodedata

    decomposed = unicodedata.normalize("NFKD", name)
    stripped = "".join(c for c in decomposed if not unicodedata.combining(c))
    cleaned = stripped.lower().replace("ʼ", "").replace("'", "").replace("-", " ").strip()

    for prefix in NAME_PREFIXES:
        # Prefixes are already accent-folded above, so fold the prefix too.
        folded = "".join(
            c for c in unicodedata.normalize("NFKD", prefix) if not unicodedata.combining(c)
        )
        if cleaned.startswith(folded):
            cleaned = cleaned[len(folded):].strip()
            break
    return cleaned


def match_municipality(tags: dict) -> int | None:
    """Find our municipality id for an OSM relation, by any of its name tags."""
    candidates = {
        normalise(tags[key])
        for key in ("name", "name:en", "name:mk", "name:sq", "int_name", "official_name")
        if tags.get(key)
    }
    if not candidates:
        return None

    for municipality_id, spellings in MUNICIPALITIES.items():
        known = {normalise(s) for s in spellings}
        if candidates & known:
            return municipality_id

    # Fall back to a prefix match: OSM sometimes appends "Municipality".
    for municipality_id, spellings in MUNICIPALITIES.items():
        for spelling in spellings:
            target = normalise(spelling)
            if any(c.startswith(target) or target.startswith(c) for c in candidates):
                return municipality_id
    return None


def parse_boundaries(payload: dict) -> list[Boundary]:
    boundaries: list[Boundary] = []
    unmatched: list[str] = []

    for element in payload.get("elements", []):
        if element.get("type") != "relation":
            continue

        tags = element.get("tags", {})
        municipality_id = match_municipality(tags)
        name = tags.get("name") or tags.get("name:en") or f"relation/{element.get('id')}"

        if municipality_id is None:
            unmatched.append(name)
            continue

        outer_ways: list[Ring] = []
        inner_ways: list[Ring] = []

        for member in element.get("members", []):
            geometry = member.get("geometry")
            if member.get("type") != "way" or not geometry:
                continue
            ring = [(point["lon"], point["lat"]) for point in geometry]
            if member.get("role") == "inner":
                inner_ways.append(ring)
            else:
                outer_ways.append(ring)

        boundary = Boundary(
            municipality_id=municipality_id,
            name=name,
            outers=stitch_rings(outer_ways),
            inners=stitch_rings(inner_ways),
        )
        if boundary.outers:
            boundaries.append(boundary)

    if unmatched:
        print(f"  note: {len(unmatched)} admin_level=8 relations did not match a Kanta "
              f"municipality and were skipped: {', '.join(sorted(set(unmatched))[:6])}")

    return boundaries


def boundaries_from_overture(records: list[dict]) -> list[Boundary]:
    """Overture hands back finished polygons, so only the name matching is shared."""
    boundaries: list[Boundary] = []
    for record in records:
        municipality_id = match_municipality(record["tags"])
        if municipality_id is None:
            continue
        boundaries.append(Boundary(
            municipality_id=municipality_id,
            name=record["tags"].get("name") or str(municipality_id),
            outers=[[tuple(point) for point in ring] for ring in record["outers"]],
            inners=[[tuple(point) for point in ring] for ring in record["inners"]],
        ))
    return boundaries


# ---------------------------------------------------------------------------------------------
# SQL generation
# ---------------------------------------------------------------------------------------------

def sql_escape(value: str) -> str:
    return value.replace("'", "''")


def write_sql(containers: list[Container], boundaries: list[Boundary], path: Path,
              source: str, regenerate: str, dumping_spots: list[int]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    out: list[str] = []
    add = out.append

    add("-- =============================================================================")
    add("-- Kanta — OpenStreetMap seed (KANTA_SPEC.md §7)")
    add("--")
    add("-- GENERATED FILE — do not edit by hand.")
    add(f"-- Regenerate with:  {regenerate}")
    add("--")
    add(f"-- Source: {source}")
    add(f"-- Containers: {len(containers)}"
        f" ({sum(c.from_bin_tag for c in containers)} of them bus-stop bins)"
        f"   Boundaries: {len(boundaries)}")
    add("--")
    add("-- Data © OpenStreetMap contributors, ODbL. The app credits this on the map (§7).")
    add("--")
    add("-- Safe to re-run: containers are matched on osm_id and never duplicated,")
    add("-- rows next to a container someone added in the app are skipped, and")
    add("-- boundaries are overwritten in place.")
    add("-- =============================================================================")
    add("set search_path to public, extensions;")
    add("")
    add("begin;")
    add("")

    # --- boundaries -------------------------------------------------------------------
    add("-- -----------------------------------------------------------------------------")
    add("-- 1. Municipality boundaries (OSM admin_level=7 — see import_osm.py for why")
    add("--    the spec's stated level 8 does not match how Skopje is tagged)")
    add("-- -----------------------------------------------------------------------------")
    if not boundaries:
        add("-- No boundaries were downloaded. municipality_at() keeps returning NULL,")
        add("-- which leaves reports unassigned rather than mis-assigned.")
    for boundary in sorted(boundaries, key=lambda b: b.municipality_id):
        add(f"-- {boundary.municipality_id}: {boundary.name}")
        add(
            f"update municipalities set geom = "
            f"st_geomfromtext('{boundary.to_wkt()}', 4326)::geography "
            f"where id = {boundary.municipality_id};"
        )
    add("")

    # --- containers -------------------------------------------------------------------
    add("-- -----------------------------------------------------------------------------")
    add("-- 2. Containers")
    add("--")
    add("-- Staged first so codes are issued by next_container_code() in import order,")
    add("-- continuing from whatever already exists rather than restarting at SK-00001.")
    add("-- -----------------------------------------------------------------------------")
    add("select sync_container_code_seq();")
    add("")
    add("-- osm_id: nodes as-is, ways negated (OSM numbers nodes and ways separately).")
    add("-- dedupe_m: how close a container added in the app may be before this row")
    add("-- counts as the same one (§4.6 radii; wider for bus-stop bins, whose point")
    add("-- is the stop's).")
    add("create temp table osm_import (")
    add("    ordinal  int,")
    add("    osm_id   bigint,")
    add("    kind     text,")
    add("    category text,")
    add("    lon      double precision,")
    add("    lat      double precision,")
    add("    dedupe_m double precision")
    add(") on commit drop;")
    add("")

    if containers:
        # Chunked so no single statement becomes unreasonably large for the
        # Supabase SQL editor to parse.
        chunk_size = 500
        for start in range(0, len(containers), chunk_size):
            chunk = containers[start:start + chunk_size]
            add("insert into osm_import (ordinal, osm_id, kind, category, lon, lat, dedupe_m) values")
            rows = [
                f"    ({start + offset + 1}, {c.osm_id}, '{c.kind}', '{c.category}', "
                f"{c.lon:.7f}, {c.lat:.7f}, "
                f"{BIN_TAG_MERGE_M if c.from_bin_tag else DUPLICATE_M[c.kind]:.0f})"
                for offset, c in enumerate(chunk)
            ]
            add(",\n".join(rows) + ";")
            add("")

    add("-- Only rows we have never imported before (idempotent re-run), and not where")
    add("-- someone already added the same kind of container in the app.")
    add("insert into containers (code, kind, category, geom, source, osm_id,")
    add("                        verified, status, status_since, created_at)")
    add("select")
    add("    next_container_code(),")
    add("    o.kind,")
    add("    o.category,")
    add("    st_setsrid(st_makepoint(o.lon, o.lat), 4326)::geography,")
    add("    'osm',")
    add("    o.osm_id,")
    add("    -- OSM data is surveyed, not user-guessed: it starts verified (§4.6).")
    add("    true,")
    add("    'ok',")
    add("    now(),")
    add("    now()")
    add("from osm_import o")
    add("where not exists (")
    add("    select 1 from containers c where c.osm_id = o.osm_id")
    add(")")
    add("and not exists (")
    add("    select 1 from containers c")
    add("     where c.source <> 'osm'")
    add("       and c.deleted_at is null")
    # Migration 0020: every bin starts with its size unknown, so an unknown one nearby
    # is the same bin as far as this check can tell.
    add("       and c.kind in (o.kind, 'unknown')")
    add("       and st_dwithin(c.geom, st_setsrid(st_makepoint(o.lon, o.lat), 4326)::geography,")
    add("                      o.dedupe_m)")
    add(")")
    add("order by o.ordinal;")
    add("")
    add("-- Dumping spots (waste_disposal + informal=yes) are not containers. Earlier")
    add("-- imports took them in; this removes them the same way admins do (soft delete).")
    if dumping_spots:
        ids = ", ".join(str(i) for i in sorted(dumping_spots))
        add("update containers")
        add("   set deleted_at = now()")
        add(" where source = 'osm'")
        add("   and deleted_at is null")
        add(f"   and osm_id in ({ids});")
    else:
        add("-- (none found in this run)")
    add("")

    # --- municipality assignment ------------------------------------------------------
    add("-- -----------------------------------------------------------------------------")
    add("-- 3. Assign municipality_id with ST_Contains (§7)")
    add("--")
    add("-- Runs over every container, not just the new ones, so re-running after the")
    add("-- boundaries land also fixes rows imported while geom was still NULL.")
    add("-- -----------------------------------------------------------------------------")
    add("update containers c")
    add("   set municipality_id = m.id")
    add("  from municipalities m")
    add(" where m.geom is not null")
    add("   and c.deleted_at is null")
    add("   and st_contains(m.geom::geometry, c.geom::geometry)")
    add("   and c.municipality_id is distinct from m.id;")
    add("")
    add("-- Suggestions too, so city stats can group them (§5.4).")
    add("update suggestions s")
    add("   set municipality_id = m.id")
    add("  from municipalities m")
    add(" where m.geom is not null")
    add("   and st_contains(m.geom::geometry, s.geom::geometry)")
    add("   and s.municipality_id is distinct from m.id;")
    add("")
    add("commit;")
    add("")

    # --- verification -----------------------------------------------------------------
    add("-- -----------------------------------------------------------------------------")
    add("-- 4. Check what landed")
    add("-- -----------------------------------------------------------------------------")
    add("select")
    add("    coalesce(m.name_en, '(unassigned)') as municipality,")
    # Counted by what OSM says (source_kind): on the map every bin starts unknown (0020).
    add("    count(*) filter (where coalesce(c.source_kind, c.kind) = 'big' and c.category = 'general') as big_general,")
    add("    count(*) filter (where coalesce(c.source_kind, c.kind) = 'big' and c.category <> 'general') as recycling,")
    add("    count(*) filter (where coalesce(c.source_kind, c.kind) = 'small') as small_cans,")
    add("    count(*) as total")
    add("from containers c")
    add("left join municipalities m on m.id = c.municipality_id")
    add("where c.source = 'osm' and c.deleted_at is null")
    add("group by m.name_en")
    add("order by total desc;")

    path.write_text("\n".join(out) + "\n", encoding="utf-8")


# ---------------------------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------------------------

def print_summary(containers: list[Container], boundaries: list[Boundary]) -> None:
    by_id = {b.municipality_id: b.name for b in boundaries}
    names = {mid: by_id.get(mid, spellings[1] if len(spellings) > 1 else spellings[0])
             for mid, spellings in MUNICIPALITIES.items()}

    buckets: dict[str, dict[str, int]] = {}
    for container in containers:
        key = names.get(container.municipality_id, "(outside / unassigned)")
        bucket = buckets.setdefault(key, {"big": 0, "recycling": 0, "small": 0})
        if container.kind == "small":
            bucket["small"] += 1
        elif container.category == "general":
            bucket["big"] += 1
        else:
            bucket["recycling"] += 1

    print()
    print("=" * 72)
    print("SUMMARY")
    print("=" * 72)
    header = f"{'Municipality':<26}{'Big':>7}{'Recycling':>12}{'Small cans':>13}{'Total':>8}"
    print(header)
    print("-" * 72)

    def sort_key(item: tuple[str, dict[str, int]]) -> tuple[int, int]:
        return (1 if item[0].startswith("(") else 0, -sum(item[1].values()))

    for name, bucket in sorted(buckets.items(), key=sort_key):
        total = sum(bucket.values())
        print(f"{name:<26}{bucket['big']:>7}{bucket['recycling']:>12}"
              f"{bucket['small']:>13}{total:>8}")

    totals = {
        "big": sum(b["big"] for b in buckets.values()),
        "recycling": sum(b["recycling"] for b in buckets.values()),
        "small": sum(b["small"] for b in buckets.values()),
    }
    print("-" * 72)
    print(f"{'TOTAL':<26}{totals['big']:>7}{totals['recycling']:>12}"
          f"{totals['small']:>13}{len(containers):>8}")
    print(f"{sum(c.from_bin_tag for c in containers)} of the small cans are bus stops "
          f"tagged bin=yes; the rest are mapped bins.")
    print()
    print(f"Municipality boundaries downloaded: {len(boundaries)}/10")

    # §7 asks for honesty about thin coverage rather than a cheerful number.
    verdict(containers, totals, boundaries)


def verdict(containers: list[Container], totals: dict[str, int], boundaries: list[Boundary]) -> None:
    print()
    print("=" * 72)

    if len(containers) == 0:
        print("NO CONTAINERS FOUND.")
        print()
        print("Skopje has essentially no waste containers mapped in OpenStreetMap.")
        print("That is a real gap in the data, not a bug in this script.")
    elif len(containers) < 150:
        print(f"ONLY {len(containers)} CONTAINERS FOUND — this is very thin coverage.")
        print()
        print("Skopje has thousands of containers in reality. OSM simply has not")
        print("had them surveyed. Treat this import as a starting skeleton, not a map.")
    elif len(containers) < 1000:
        print(f"{len(containers)} containers found — partial coverage.")
        print()
        print("Enough to launch with, but large parts of the city will look empty.")
    else:
        print(f"{len(containers)} containers found — good coverage to start from.")

    if len(containers) < 1000:
        print()
        print("What to do about it:")
        print()
        print("  * This is exactly what §4.6 'Map your street' is for. Walk your own")
        print("    neighbourhood and use the in-app Add container flow — each account")
        print("    can add 2, and yours is an admin account with no limit.")
        print("  * Every container you add starts unverified (dashed marker) until two")
        print("    neighbours tap 'Yes, it's here', so the map stays honest as it fills.")
        print("  * The admin coverage map shows which parts of Skopje nobody has")
        print("    checked yet, so you can see where to walk next.")
        print()
        print("  Consider adding what you map back to OpenStreetMap too — it is the")
        print("  same data, and it helps everyone rather than only this app.")

    if len(boundaries) < 10:
        missing = sorted(set(MUNICIPALITIES) - {b.municipality_id for b in boundaries})
        print()
        print(f"WARNING: only {len(boundaries)}/10 municipality boundaries were matched.")
        print(f"Missing ids: {missing}")
        print("Containers in those areas will have municipality_id NULL, so they will")
        print("show as '(unassigned)' in city stats until the boundaries are fixed.")

    print("=" * 72)


# ---------------------------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------------------------

def main() -> int:
    parser = argparse.ArgumentParser(
        description="Import Skopje waste containers and municipality boundaries from OSM.",
    )
    parser.add_argument("--out", type=Path, default=OUTPUT_SQL,
                        help=f"SQL file to write (default: {OUTPUT_SQL})")
    parser.add_argument("--source", choices=("overpass", "overture"), default="overpass",
                        help="where to read OSM data from: live Overpass (default), or "
                             "Overture Maps' monthly copy when Overpass is unreachable")
    parser.add_argument("--cache-dir", type=Path, default=Path("tools/import_osm/.cache"),
                        help="where to cache downloaded responses")
    parser.add_argument("--no-cache", action="store_true",
                        help="ignore any cached response and download again")
    parser.add_argument("--overpass-url", default=OVERPASS_URL,
                        help="alternative Overpass instance")
    parser.add_argument("--overture-release", default=None,
                        help="Overture release, e.g. 2026-09-23.1 (default: the newest)")
    parser.add_argument("--skip-boundaries", action="store_true",
                        help="containers only (boundaries are the slow half)")
    arguments = parser.parse_args()

    cache_dir = None if arguments.no_cache else arguments.cache_dir

    print("Kanta — OpenStreetMap import (spec §7)")

    if arguments.source == "overpass":
        print(f"Overpass: {arguments.overpass_url}")
        source_note = "OpenStreetMap via the Overpass API"
        regenerate = "python3 tools/import_osm/import_osm.py"

        print("\n1. Containers")
        # Named after the query, so a response cached for an older query is
        # never mistaken for this one.
        query_hash = hashlib.sha1(CONTAINERS_QUERY.encode("utf-8")).hexdigest()[:8]
        elements = overpass(
            CONTAINERS_QUERY,
            cache_dir / f"containers-{query_hash}.json" if cache_dir else None,
            "containers",
            arguments.overpass_url,
        ).get("elements", [])

        boundaries: list[Boundary] = []
        if arguments.skip_boundaries:
            print("\n2. Boundaries — skipped (--skip-boundaries)")
        else:
            print("\n2. Municipality boundaries")
            boundary_payload = overpass(
                BOUNDARIES_QUERY,
                cache_dir / "boundaries.json" if cache_dir else None,
                "boundaries",
                arguments.overpass_url,
            )
            boundaries = parse_boundaries(boundary_payload)
    else:
        import overture  # pyarrow lives behind this import; Overpass needs nothing

        release = arguments.overture_release or overture.latest_release()
        print(f"Overture Maps release {release}")
        source_note = f"OpenStreetMap via Overture Maps release {release}"
        regenerate = "python3 tools/import_osm/import_osm.py --source overture"

        def cached(name: str, download):
            path = cache_dir / f"overture-{release}-{name}.json" if cache_dir else None
            if path and path.exists():
                print(f"  using cached {name}: {path}")
                return json.loads(path.read_text())
            print(f"  reading {name} from Overture…")
            data = download(release)
            if path:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(json.dumps(data))
            return data

        print("\n1. Containers")
        elements = cached("containers", overture.fetch_elements)

        boundaries = []
        if arguments.skip_boundaries:
            print("\n2. Boundaries — skipped (--skip-boundaries)")
        else:
            print("\n2. Municipality boundaries")
            boundaries = boundaries_from_overture(cached("boundaries", overture.fetch_boundaries))

    if not arguments.skip_boundaries:
        print(f"  {len(boundaries)} municipalities matched and assembled")

    # --- classify ---------------------------------------------------------------------
    containers: list[Container] = []
    dumping_spots: list[tuple[int, float, float]] = []   # (node id, lon, lat)
    skipped = 0
    for element in elements:
        container = to_container(element)
        if container is not None:
            containers.append(container)
        elif element.get("type") == "node" and is_dumping_spot(element.get("tags", {})):
            dumping_spots.append((element["id"], element["lon"], element["lat"]))
        else:
            skipped += 1

    # Stable order, so a fresh database gets the same SK- codes every run:
    # mapped containers first, then bus-stop bins; nodes before ways; by id.
    containers.sort(key=lambda c: (c.from_bin_tag, c.osm_id < 0, abs(c.osm_id)))

    # --- keep what is inside the ten municipalities ------------------------------------
    # The queries use a box that also covers neighbouring municipalities. The
    # boundaries decide, here for the summary and again in the database with
    # ST_Contains (§7).
    for container in containers:
        for boundary in boundaries:
            if boundary.contains(container.lon, container.lat):
                container.municipality_id = boundary.municipality_id
                break

    if len(boundaries) == len(MUNICIPALITIES):
        outside = sum(c.municipality_id is None for c in containers)
        containers = [c for c in containers if c.municipality_id is not None]
        dumping_spots = [
            spot for spot in dumping_spots
            if any(b.contains(spot[1], spot[2]) for b in boundaries)
        ]
    else:
        outside = 0
        print("  WARNING: without all ten boundaries nothing can be clipped to the city;")
        print("  everything in the search box is kept.")

    containers, merged = drop_bin_tag_duplicates(containers)

    print(f"\n  {len(containers)} containers"
          f" ({sum(c.from_bin_tag for c in containers)} from bus stops tagged bin=yes)")
    if outside:
        print(f"  {outside} outside the ten municipalities, left out")
    if merged:
        print(f"  {merged} bin=yes points already covered by a mapped can, left out")
    if dumping_spots:
        print(f"  {len(dumping_spots)} informal dumping spots (not containers), left out"
              " and removed if an earlier import took them in")
    if skipped:
        print(f"  {skipped} elements skipped (not a container type)")

    # --- write ------------------------------------------------------------------------
    write_sql(containers, boundaries, arguments.out, source_note, regenerate,
              [spot[0] for spot in dumping_spots])
    size_kb = arguments.out.stat().st_size / 1024
    print(f"\n3. Wrote {arguments.out} ({size_kb:.0f} KB)")

    print_summary(containers, boundaries)

    print()
    print("Next: paste the contents of")
    print(f"  {arguments.out}")
    print("into the Supabase SQL editor and run it (after the migrations and")
    print("seed/0001_municipalities.sql). See tools/import_osm/README.md.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\ninterrupted")
        sys.exit(130)
