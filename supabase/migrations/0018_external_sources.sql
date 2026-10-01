-- =============================================================================
-- 0018 — Containers from outside OSM (tools/import_external)
--
-- Two new kinds of import:
--   * 'mapillary' — trash cans that Mapillary detected in street-level photos
--   * 'city'      — a list from the operator (Комунална хигиена, Пакомак)
--
-- external_id is the id in the source (a Mapillary feature id, or the id column
-- of a list), so re-running an import never duplicates a row.
-- Idempotent: safe to re-run.
-- =============================================================================
set search_path to public, extensions;

alter table containers add column if not exists external_id text;

alter table containers drop constraint if exists containers_source_check;
alter table containers add constraint containers_source_check
    check (source in ('osm', 'user', 'city', 'mapillary'));

create unique index if not exists containers_source_external_id
    on containers (source, external_id)
    where external_id is not null;
