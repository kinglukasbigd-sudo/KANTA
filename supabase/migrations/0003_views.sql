-- =============================================================================
-- Kanta — 0003 public views
-- KANTA_SPEC.md §6 (anon reads reports "without user_id exposed — use a view")
-- §8 (no reporter identity shown publicly; public views show "a neighbour")
-- =============================================================================
set search_path to public, extensions;

-- security_invoker = off (the default for views) would run as the view owner and
-- bypass RLS on the base table. That is exactly what we want here: the view is
-- the sanctioned public window onto reports, and it never selects user_id.

-- Open and resolved reports, with the reporter's identity stripped.
create or replace view reports_public as
select
    r.id,
    r.container_id,
    r.kind,
    r.photo_path,
    r.note,
    r.state,
    r.created_at,
    r.resolved_at,
    -- Enough to render "X neighbours reported this too" without exposing who.
    (select count(*) from report_confirmations c
      where c.report_id = r.id and c.kind = 'me_too')       as me_too_count,
    (select count(*) from report_confirmations c
      where c.report_id = r.id and c.kind = 'resolved')     as resolved_count,
    -- Hours the problem has been open — drives "full for 31 h" (§4.1).
    extract(epoch from (coalesce(r.resolved_at, now()) - r.created_at)) / 3600.0 as age_hours
from reports r
join containers ct on ct.id = r.container_id
where r.state in ('open', 'resolved')
  and ct.deleted_at is null;

-- Suggestions without the author (§8). votes is already aggregate, so it is safe.
create or replace view suggestions_public as
select
    s.id,
    st_y(s.geom::geometry) as lat,
    st_x(s.geom::geometry) as lon,
    s.reason,
    s.note,
    s.photo_path,
    s.votes,
    s.state,
    s.placed_container_id,
    s.municipality_id,
    s.created_at
from suggestions s;

-- Live containers only. The app's map and detail sheet read this, never the table.
create or replace view containers_public as
select
    c.id,
    c.code,
    c.kind,
    c.category,
    st_y(c.geom::geometry) as lat,
    st_x(c.geom::geometry) as lon,
    c.municipality_id,
    c.status,
    c.status_since,
    c.source,
    c.verified,
    c.created_at
from containers c
where c.deleted_at is null;

comment on view reports_public is
    'Reports with user_id removed (§6 RLS, §8 privacy). Anon-readable.';
comment on view suggestions_public is
    'Suggestions with user_id removed (§6 RLS, §8 privacy). Anon-readable.';
comment on view containers_public is
    'Containers excluding soft-deleted rows (§4.6). Anon-readable.';
