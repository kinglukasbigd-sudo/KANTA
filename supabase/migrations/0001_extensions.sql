-- =============================================================================
-- Kanta — 0001 extensions
-- KANTA_SPEC.md §6 (Postgres + PostGIS), §5.1 (hourly expiry via pg_cron)
-- =============================================================================

-- Supabase convention: extensions live in their own schema, not in public.
create schema if not exists extensions;

-- PostGIS: every geometry in the spec is geography(Point|MultiPolygon, 4326).
create extension if not exists postgis with schema extensions;

-- pg_cron drives the hourly report-expiry job (§5.1). It can only be installed
-- into the database named `postgres`, which is the one Supabase gives you.
create extension if not exists pg_cron;

-- Used by delete_my_account() and by the admin RPCs to read auth.users.
create extension if not exists pgcrypto with schema extensions;

-- PostGIS functions are referenced unqualified throughout these migrations.
alter database postgres set search_path to public, extensions;

-- Make them resolvable inside this session too, so the rest of the migrations
-- run without a reconnect.
set search_path to public, extensions;
