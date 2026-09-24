-- =============================================================================
-- Kanta — 0016 suggestions: "vote for this one instead", detail, photos
-- KANTA_SPEC.md §4.4, §5.3
--
-- 1. open_suggestion_near(): before sending, the app asks whether an open
--    suggestion already stands within the 50 m merge radius, so it can offer
--    "Vote for this one instead" (§4.4). The radius lives here, the same 50 m
--    submit_suggestion() merges on — the app never passes its own.
-- 2. suggestion_detail(): one suggestion for the map's detail sheet, with the
--    caller's vote.
-- 3. Storage: suggestion photos go to suggestions/{user_id}/{uuid}.jpg.
-- =============================================================================
set search_path to public, extensions;

-- -----------------------------------------------------------------------------
-- 1. The open suggestion submit_suggestion() would merge into, if any.
-- -----------------------------------------------------------------------------
create or replace function open_suggestion_near(
    p_lon double precision,
    p_lat double precision
)
returns table (
    id         uuid,
    lon        double precision,
    lat        double precision,
    reason     text,
    note       text,
    votes      int,
    distance_m double precision,
    created_at timestamptz,
    i_voted    boolean
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    with origin as (
        select st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography as g
    )
    select
        s.id,
        st_x(s.geom::geometry), st_y(s.geom::geometry),
        s.reason, s.note, s.votes,
        st_distance(s.geom, o.g),
        s.created_at,
        exists (
            select 1 from suggestion_votes v
             where v.suggestion_id = s.id and v.user_id = auth.uid()
        )
    from suggestions s, origin o
    -- §5.3 merge radius: the same 50 m as submit_suggestion() in 0007.
    where s.state = 'open'
      and st_dwithin(s.geom, o.g, 50)
    order by s.geom <-> o.g
    limit 1;
$$;

-- -----------------------------------------------------------------------------
-- 2. One suggestion (map marker → detail sheet).
-- -----------------------------------------------------------------------------
create or replace function suggestion_detail(p_suggestion_id uuid)
returns table (
    id              uuid,
    lon             double precision,
    lat             double precision,
    reason          text,
    note            text,
    photo_path      text,
    votes           int,
    state           text,
    municipality_id smallint,
    created_at      timestamptz,
    i_voted         boolean
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    select
        s.id,
        st_x(s.geom::geometry), st_y(s.geom::geometry),
        s.reason, s.note, s.photo_path, s.votes, s.state, s.municipality_id, s.created_at,
        exists (
            select 1 from suggestion_votes v
             where v.suggestion_id = s.id and v.user_id = auth.uid()
        )
    from suggestions s
    where s.id = p_suggestion_id;
$$;

-- -----------------------------------------------------------------------------
-- 3. Suggestion photos: suggestions/{user_id}/{uuid}.jpg (§6 storage paths).
-- -----------------------------------------------------------------------------
drop policy if exists photos_insert_own_folder on storage.objects;
create policy photos_insert_own_folder on storage.objects
    for insert to authenticated
    with check (
        bucket_id = 'photos'
        and (storage.foldername(name))[1] in ('reports', 'containers', 'container_requests', 'suggestions')
        and (storage.foldername(name))[2] = auth.uid()::text
        and lower(right(name, 4)) = '.jpg'
    );

-- -----------------------------------------------------------------------------
-- Grants. Browsing suggestions needs no account (§2); i_voted is simply false.
-- -----------------------------------------------------------------------------
revoke execute on function
    open_suggestion_near(double precision, double precision),
    suggestion_detail(uuid)
from public, anon, authenticated;

grant execute on function
    open_suggestion_near(double precision, double precision),
    suggestion_detail(uuid)
to anon, authenticated;
