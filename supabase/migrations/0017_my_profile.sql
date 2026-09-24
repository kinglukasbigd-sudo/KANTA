-- =============================================================================
-- Kanta — 0017 "My reports & profile"
-- KANTA_SPEC.md §4.5 screen 11, §5.4
--
-- 1. my_reports() gains what the profile list shows: the container's kind,
--    position and municipality (tap → container on the map), and the photo of
--    the confirmation that resolved the report — the "after" of §5.4's
--    before/after. The return type changes, so the old function is dropped.
-- 2. my_suggestions(): suggestions the user made or voted for.
-- =============================================================================
set search_path to public, extensions;

drop function if exists my_reports(int);

create or replace function my_reports(p_limit int default 100)
returns table (
    report_id          uuid,
    container_id       uuid,
    container_code     text,
    container_kind     text,
    container_lon      double precision,
    container_lat      double precision,
    municipality_id    smallint,
    kind               text,
    state              text,
    photo_path         text,
    note               text,
    created_at         timestamptz,
    resolved_at        timestamptz,
    me_too_count       bigint,
    -- §5.4 before/after: the newest 'resolved' confirmation that came with a
    -- photo. Null when two neighbours resolved it without one (§5.1).
    resolved_photo_path text
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    select
        r.id, r.container_id, c.code, c.kind,
        st_x(c.geom::geometry), st_y(c.geom::geometry),
        c.municipality_id,
        r.kind, r.state, r.photo_path, r.note,
        r.created_at, r.resolved_at,
        (select count(*) from report_confirmations rc
          where rc.report_id = r.id and rc.kind = 'me_too'),
        (select rc.photo_path from report_confirmations rc
          where rc.report_id = r.id
            and rc.kind = 'resolved'
            and rc.photo_path is not null
          order by rc.created_at desc
          limit 1)
    from reports r
    join containers c on c.id = r.container_id
    where r.user_id = auth.uid()
    order by r.created_at desc
    limit greatest(p_limit, 1);
$$;

create or replace function my_suggestions(p_limit int default 100)
returns table (
    id              uuid,
    lon             double precision,
    lat             double precision,
    reason          text,
    votes           int,
    state           text,
    municipality_id smallint,
    created_at      timestamptz,
    -- The author's vote is automatic (0007), so "voted" is true for both; this
    -- tells them apart.
    i_authored      boolean
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    select
        s.id,
        st_x(s.geom::geometry), st_y(s.geom::geometry),
        s.reason, s.votes, s.state, s.municipality_id, s.created_at,
        s.user_id is not distinct from auth.uid()
    from suggestions s
    where auth.uid() is not null
      and (
          s.user_id = auth.uid()
          or exists (
              select 1 from suggestion_votes v
               where v.suggestion_id = s.id and v.user_id = auth.uid()
          )
      )
    order by s.created_at desc
    limit greatest(p_limit, 1);
$$;

revoke execute on function my_reports(int), my_suggestions(int) from public, anon, authenticated;
grant execute on function my_reports(int), my_suggestions(int) to authenticated;
