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
| `--no-cache` | Ignore the cached responses and download again |
| `--skip-boundaries` | Containers only (boundaries are the slow half). Nothing can be clipped to the city without them, so everything in the search box is kept |
| `--overpass-url URL` | Use a different Overpass instance |
| `--source overture` | Read OSM data from Overture Maps instead of Overpass (see below) |
| `--overture-release R` | Which Overture release to read (default: the newest) |
| `--out PATH` | Write somewhere other than `supabase/seed/containers.sql` |

Responses are cached in `tools/import_osm/.cache/` (git-ignored) so repeated runs
don't hammer a free service. Delete that folder, or pass `--no-cache`, to refresh.

If Overpass returns **504**, it is overloaded, not broken. The script backs off and
retries three times; if it still fails, wait a few minutes or try:

```bash
python3 tools/import_osm/import_osm.py --overpass-url https://overpass.kumi.systems/api/interpreter
```

### When Overpass is unreachable: `--source overture`

[Overture Maps](https://overturemaps.org) republishes OpenStreetMap every month as
GeoParquet in a public S3 bucket, with each feature's original OSM tags and id.
The importer can read that copy instead:

```bash
pip install pyarrow
python3 tools/import_osm/import_osm.py --source overture
```

It reads only the parts of the files that cover Skopje (a few MB), takes the
containers from `base/infrastructure` and the municipality boundaries from
`divisions/division_area`, and then runs the same rules as the Overpass path.
There are two differences. Overture is up to a month behind live OSM. It also
keeps only the feature types it recognises: every waste feature and bus stop
is there, but a rare `bin=yes` on something like a picnic table is only seen
through Overpass. `--overture-release 2026-09-23.1`
pins a release; the default is the newest.

The data is still © OpenStreetMap contributors under the ODbL, so the
attribution doesn't change.

## What it does

1. Asks Overpass for everything that could be a bin in a box around Skopje:
   `waste_disposal`, `waste_basket` and `recycling`, as nodes **and** ways (a
   few containers are drawn as small areas; a way becomes the centre of its
   bounding box), plus anything tagged **`bin=yes`**. Mappers put `bin=yes`
   on bus stops that have a bin.
2. Downloads the municipality boundary relations and stitches their member ways
   into closed rings, then into `MULTIPOLYGON` WKT. No GIS library needed.
   Containers outside the ten municipalities are dropped.
3. Maps tags to Kanta's model per §7:
   - `waste_disposal` → big / general, **except** `informal=yes`, which marks a
     place people dump rubbish with no container. Those are left out, and the
     SQL soft-deletes any that an earlier import took in.
   - `waste_basket` → small / general
   - `recycling` → big. Recycling centres (`recycling_type=centre`) are yards,
     not containers, and are left out. The category comes from the
     `recycling:*` tags, with OSM's finer materials folded into Kanta's three:
     `glass_bottles` counts as glass, `plastic_bottles` and `plastic_packaging`
     as plastic, `cardboard` as paper. More than one material, or none tagged,
     becomes `mixed_recycling`.
   - `bin=yes` → small / general, at the bus stop's position. It is dropped if
     a mapped `waste_basket` is within 20 m, or another `bin=yes` point is
     within 10 m.
4. Emits SQL that stages the rows in a temp table, then inserts only containers
   whose `osm_id` isn't already present, taking codes from `next_container_code()`
   so numbering continues from whatever exists rather than restarting. A row is
   also skipped when someone already added the same kind of container in the
   app close by: within 10 m for big containers and 5 m for small ones (the
   §4.6 duplicate radii), or 20 m for `bin=yes` points.
   Way ids are stored negated in `osm_id`, because OSM numbers nodes and ways
   separately and the two can collide. Rows imported earlier are never changed.
5. Assigns `municipality_id` with `ST_Contains`, for **all** containers — so
   re-running after the boundaries land also fixes rows imported earlier.
6. Prints a per-municipality summary.

Sizes: since migration 0020 every bin is shown with its size **unknown** until
someone standing next to it says Small or Big in the app. The size the mapping
above gives is kept in `containers.source_kind`, and the summary at the end of
the SQL counts by it.

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

KANTA_SPEC.md §7 now says level 7.

## What's actually in OSM right now

As of the last run (Overture release 2026-09-23.1, October 2026): **220
containers**. That is 28 big general, 5 recycling and 187 small cans. Of the
small cans, 56 are mapped bins and 131 are bus stops tagged `bin=yes`. All 10
boundaries resolved.

| Municipality | Big | Recycling | Small cans | of which bus stops | Total |
|---|---:|---:|---:|---:|---:|
| Карпош | 15 | 3 | 60 | 33 | 78 |
| Центар | 6 | 1 | 64 | 37 | 71 |
| Аеродром | 1 | 1 | 27 | 27 | 29 |
| Гази Баба | 0 | 0 | 21 | 19 | 21 |
| Кисела Вода | 5 | 0 | 7 | 7 | 12 |
| Чаир | 0 | 0 | 8 | 8 | 8 |
| Сарај | 1 | 0 | 0 | 0 | 1 |
| Бутел, Ѓорче Петров, Шуто Оризари | 0 | 0 | 0 | 0 | 0 |

The first import found 91. The query is wider now, which adds 131 bus-stop bins,
1 new container in Сарај and 2 drawn as areas. It also drops 5 informal dumping
spots that the first import had counted as containers.

**This is still thin.** Skopje has thousands of containers, and only about 90 of
them are mapped as bins in OSM. Three municipalities have nothing at all. Treat
this import as a skeleton, not a map.

Places that know more, but publish no open data this importer can read:

- **ЈП Комунална хигиена** has a coverage map of the city at
  [khigiena.com.mk/map](https://khigiena.com.mk/map/?region=5).
- **Пакомак** publishes a map of its recycling containers. By the company's
  count there are about 1,500 glass banks across the country, and 113
  underground containers at about 40 places in Skopje, installed with the City.
- **Mapillary** street-level imagery has automatic "trash can" detections, which
  you can read with a free API token.

Use any of them only with the owner's permission, or under a licence that is
compatible with the ODbL.

That gap is what §4.6 "Map your street" exists to close — walk your neighbourhood
and use the in-app Add container flow. Your admin account has no 2-container
limit, and the admin coverage map shows which areas nobody has checked yet.

If you do map an area, consider adding it to OpenStreetMap as well. It's the same
survey work, and it helps everyone rather than only this app. On a bus stop,
`bin=yes` / `bin=no` is the quickest way to record whether it has a bin.

## Attribution

§7 requires the map to credit **"© OpenStreetMap contributors · OpenFreeMap"**.
The data is ODbL-licensed; this is a licence condition, not a courtesy. Not yet
implemented — it belongs with the map screen.
