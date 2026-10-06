# Containers from outside OpenStreetMap

OSM has about 220 of Skopje's containers (see `tools/import_osm`). This tool
adds containers from two other kinds of source. Both write SQL that you run
in the Supabase SQL editor **after migration 0018 and `seed/containers.sql`**.
The tool reads the municipality boundaries back from that seed.

Standard library only, except the Mapillary command (see below).

Everything imported starts **unverified**: it shows the dashed marker until two
neighbours tap "Yes, it's here", and two "missing" reports remove it, exactly
like a container added in the app (§4.6). Re-running is safe. Rows are matched
on `(source, external_id)`, and a row is skipped when a container of the same
kind is already close by, whatever its source.

## Mapillary trash-can detections

Mapillary detects trash cans in its street-level photos automatically.

1. Get a free client token at https://www.mapillary.com/dashboard/developers
   (register an app; read access is enough).
2. Run:

   ```bash
   pip install mapbox-vector-tile
   MAPILLARY_TOKEN='MLY|…' python3 tools/import_external/import_external.py mapillary
   ```

3. Run `supabase/seed/mapillary.sql` in the SQL editor.

The tool reads Mapillary's map-feature **vector tiles**
(`tiles.mapillary.com`, zoom 14). Its search API (`graph.mapillary.com`)
accepts the token but returns no data for any area, so tiles are the route
that works. Tiles are cached in `tools/import_external/.cache/` (git-ignored).

Mapillary has one class for all trash cans, from street bins to 1,100-litre
containers, so it cannot say how big a bin is. Like every bin (migration 0020),
detections arrive with their size **unknown** and the universal marker; the
size the importer guessed is kept in `source_kind`. People standing next to a
bin say Small or Big in the app: the first answer marks it, after that the
majority decides. Two detections within 3 m count as one can, and a detection
within 10 m of an existing bin of the same or unknown size is skipped.

**Age.** Most of Skopje's imagery is from 2019. Of the 5,159 detections in the
city (October 2026), about 3,900 were last seen in 2020 or earlier and 1,261
since 2021. Old sightings may be gone. They start unverified, so neighbours
confirm the real ones and two "missing" reports remove the rest. To import
only recent ones:

```bash
MAPILLARY_TOKEN='MLY|…' python3 tools/import_external/import_external.py mapillary --seen-since 2021
```

**Before release**, check Mapillary's current terms for using map features in
an app, and add the credit they ask for next to the OSM one.

## An operator's list (Пакомак, Комунална хигиена)

If an operator shares its container locations (see `REQUEST_LETTER.md`), save
them as one of:

| Format | What it needs |
|---|---|
| `.csv` | `lat`/`latitude` and `lon`/`lng`/`longitude` columns; decimal commas are fine. Optional: `id`, `kind` (`big`/`small`), `category` |
| `.geojson` | Point features; optional `id`, `kind`, `category` properties |
| `.kml` | Placemarks with a Point, e.g. a Google My Maps export; optional `kind` / `category` in ExtendedData |

```bash
python3 tools/import_external/import_external.py list pakomak.kml --name pakomak \
    --category glass
```

This writes `supabase/seed/list_pakomak.sql`. `--kind` and `--category` set the
values for rows that don't have their own (defaults: `big`, `general`). Rows
come in with `source = 'city'`. Add `--verified` only for a list you trust
completely, such as one from the operator itself.

Import a list only with the operator's permission, or under a licence that
allows it.
