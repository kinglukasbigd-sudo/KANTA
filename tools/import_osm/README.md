# OSM import (spec §7)

Pulls Skopje's existing waste containers and municipality boundaries from
OpenStreetMap and writes `supabase/seed/containers.sql`.

## Run it

From the project root:

```bash
python3 tools/import_osm/import_osm.py
```

Python 3.10+ only — **standard library, nothing to install**. Takes about a
minute, mostly waiting on Overpass.

Then open `supabase/seed/containers.sql`, paste it into the Supabase SQL editor
and run it. It must come **after** the migrations and `seed/0001_municipalities.sql`,
because it updates the municipality rows those create.

Re-running is safe at both ends: containers are matched on `osm_id` and never
duplicated, and boundaries are overwritten in place.

## Options

| Flag | Why |
|---|---|
| `--no-cache` | Ignore the cached Overpass responses and re-query |
| `--skip-boundaries` | Containers only (boundaries are the slow half) |
| `--overpass-url URL` | Use a different Overpass instance |
| `--out PATH` | Write somewhere other than `supabase/seed/containers.sql` |

Responses are cached in `tools/import_osm/.cache/` (git-ignored) so repeated runs
don't hammer a free service. Delete that folder, or pass `--no-cache`, to refresh.

If Overpass returns **504**, it is overloaded, not broken. The script backs off and
retries three times; if it still fails, wait a few minutes or try:

```bash
python3 tools/import_osm/import_osm.py --overpass-url https://overpass.kumi.systems/api/interpreter
```

## What it does

1. Runs the §7 Overpass query for `waste_disposal`, `waste_basket` and
   `recycling` + `recycling_type=container` nodes in Skopje.
2. Downloads the municipality boundary relations and stitches their member ways
   into closed rings, then into `MULTIPOLYGON` WKT. No GIS library needed.
3. Maps tags to Kanta's model per §7:
   - `waste_disposal` → big / general
   - `waste_basket` → small / general
   - `recycling` → big, category from `recycling:glass|paper|plastic`; more than
     one material (or none tagged) becomes `mixed_recycling`
4. Emits SQL that stages the rows in a temp table, then inserts only containers
   whose `osm_id` isn't already present, taking codes from `next_container_code()`
   so numbering continues from whatever exists rather than restarting.
5. Assigns `municipality_id` with `ST_Contains`, for **all** containers — so
   re-running after the boundaries land also fixes rows imported earlier.
6. Prints a per-municipality summary.

Imported containers start `verified = true`: OSM data is surveyed, not guessed,
so it shouldn't wear the dashed unverified marker from §4.6.

## The spec is wrong about admin_level

§7 says municipality boundaries are `admin_level=8`. They are not. In OSM,
Skopje is tagged:

| Level | What |
|---|---|
| 7 | The ten municipalities — `Општина Карпош` / "Municipality of Karposh" |
| 8 | The City of Skopje itself |
| 9 | Same-named settlement relations |

Querying level 8 returns exactly one relation — the city — and no municipalities,
which is why the first run matched 0 of 10. The script asks for levels 7 and 8 and
keeps whatever matches the ten names, stripping the `Општина` / `Municipality of`
prefix first. It also handles the transliterations OSM actually uses (`Chair` for
Чаир, `Karposh` for Карпош).

Worth correcting in KANTA_SPEC.md §7.

## What's actually in OSM right now

As of the last run: **91 containers** — 30 big general, 5 recycling, 56 small cans.
All 10 boundaries resolved.

| Municipality | Big | Recycling | Small cans | Total |
|---|---:|---:|---:|---:|
| Карпош | 18 | 3 | 27 | 48 |
| Центар | 6 | 1 | 27 | 34 |
| Кисела Вода | 5 | 0 | 0 | 5 |
| Гази Баба | 0 | 0 | 2 | 2 |
| Аеродром | 1 | 1 | 0 | 2 |
| Бутел, Чаир, Ѓорче Петров, Сарај, Шуто Оризари | 0 | 0 | 0 | 0 |

**This is very thin.** Skopje has thousands of containers; OSM has 91 of them,
concentrated in two municipalities. Five have nothing at all. Treat this import as
a skeleton, not a map.

That gap is what §4.6 "Map your street" exists to close — walk your neighbourhood
and use the in-app Add container flow. Your admin account has no 2-container
limit, and the admin coverage map shows which areas nobody has checked yet.

If you do map an area, consider adding it to OpenStreetMap as well. It's the same
survey work, and it helps everyone rather than only this app.

## Attribution

§7 requires the map to credit **"© OpenStreetMap contributors · OpenFreeMap"**.
The data is ODbL-licensed; this is a licence condition, not a courtesy. Not yet
implemented — it belongs with the map screen.
