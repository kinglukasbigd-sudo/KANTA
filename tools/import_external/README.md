# Containers from outside OpenStreetMap

OSM has about 220 of Skopje's containers (see `tools/import_osm`). This tool
adds containers from two other kinds of source. Both write SQL that you run
in the Supabase SQL editor **after migration 0018 and `seed/containers.sql`**.
The tool reads the municipality boundaries back from that seed.

Standard library only — nothing to install.

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
   MAPILLARY_TOKEN='MLY|…' python3 tools/import_external/import_external.py mapillary
   ```

3. Run `supabase/seed/mapillary.sql` in the SQL editor.

The tool covers the city in tiles and splits any tile that comes back full.
Responses are cached in `tools/import_external/.cache/` (git-ignored). Every
detection is imported as a **small** can, because Mapillary has one class for
all trash cans. Detections within 10 m of an existing small can are skipped.
Detections are automatic and sometimes wrong, which is why they start
unverified.

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
