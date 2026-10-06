-- =============================================================================
-- Kanta — 0020 Bin size: every bin starts unknown, people say Small or Big
-- KANTA_SPEC.md §4.6 "Bin size"
--
-- No source can be trusted to say how big a bin is: Mapillary has one class for
-- every trash can, and OSM in Skopje barely tags it. So every bin on the map
-- starts with its size unknown, drawn with one universal marker, and the person
-- standing next to it says Small or Big:
--
--   * the first answer marks the bin right away;
--   * after that the size is the majority of everyone's answers, a tie keeping
--     what the map shows, so two people who disagree with the first one switch
--     it;
--   * one answer per person per bin; answering again replaces it;
--   * answering means you are there (50 m), so on an unverified bin it also
--     counts as "Yes, it's here";
--   * an admin's answer sets the size at once, from anywhere.
--
-- This replaces 0019's "It's here, but it's a big container" (two votes before
-- anything changed): its function and its column are removed.
--
-- The size each source claimed is kept in containers.source_kind, so the reset
-- below can be undone with:
--   update containers set kind = source_kind where kind = 'unknown' and source_kind is not null;
--
-- Idempotent: safe to re-run. The one-time reset runs only on the first run.
-- =============================================================================
set search_path to public, extensions;

-- -----------------------------------------------------------------------------
-- 1. kind = 'unknown', and the size each source claimed
-- -----------------------------------------------------------------------------
alter table containers drop constraint if exists containers_kind_check;
alter table containers add constraint containers_kind_check
    check (kind in ('big', 'small', 'unknown'));

do $reset$
begin
    if not exists (
        select 1 from information_schema.columns
         where table_schema = 'public' and table_name = 'containers' and column_name = 'source_kind'
    ) then
        alter table containers add column source_kind text
            check (source_kind in ('big', 'small'));

        -- Every bin, whatever its source, starts unknown (decided 2026-10).
        update containers
           set source_kind = kind,
               kind = 'unknown'
         where kind in ('big', 'small');
    end if;
end
$reset$;

-- Imports (OSM, Mapillary, operator lists) keep arriving with a guessed size.
-- Keep the guess, show the bin as unknown. A container someone added in the app
-- ('user') keeps the size they chose: they were standing next to it.
create or replace function containers_import_unknown_kind()
returns trigger
language plpgsql
as $$
begin
    if new.source <> 'user' and new.kind <> 'unknown' then
        new.source_kind := new.kind;
        new.kind := 'unknown';
    end if;
    return new;
end;
$$;

drop trigger if exists containers_import_unknown_kind on containers;
create trigger containers_import_unknown_kind
    before insert on containers
    for each row execute function containers_import_unknown_kind();

-- -----------------------------------------------------------------------------
-- 2. Answers
-- -----------------------------------------------------------------------------
create table if not exists container_size_votes (
    container_id uuid not null references containers (id) on delete cascade,
    user_id      uuid not null references auth.users (id) on delete cascade,
    kind         text not null check (kind in ('big', 'small')),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    primary key (container_id, user_id)
);

-- Same model as container_confirmations (0013): read your own, write only
-- through the RPCs below.
alter table container_size_votes enable row level security;
revoke all on container_size_votes from anon, authenticated;
drop policy if exists container_size_votes_read_own on container_size_votes;
create policy container_size_votes_read_own on container_size_votes
    for select to authenticated using (user_id = auth.uid());
grant select on container_size_votes to authenticated;

-- 0019's two-vote experiment, superseded by the answers above.
drop function if exists confirm_container_exists_as(uuid, double precision, double precision, text);
alter table container_confirmations drop constraint if exists container_confirmations_seen_kind_check;
alter table container_confirmations drop column if exists seen_kind;

-- Changes only the size. The category stays: a glass bank someone first called
-- small must still be a glass bank when the next person calls it big.
create or replace function set_container_kind(p_id uuid, p_kind text)
returns void
language sql
security definer
set search_path = public, extensions
as $$
    update containers
       set kind = p_kind
     where id = p_id
       and deleted_at is null
       and kind <> p_kind;
$$;

-- "May this person answer?" — signed in and within 50 m, the same distance as
-- "Yes, it's here". Shared by the reads and the write below.
create or replace function can_vote_container_size(
    p_container_id uuid,
    p_lon          double precision,
    p_lat          double precision
)
returns boolean
language sql
stable
security definer
set search_path = public, extensions
as $$
    select
        auth.uid() is not null
        and p_lon is not null and p_lat is not null
        and exists (
            select 1
              from containers c
             where c.id = p_container_id
               and c.deleted_at is null
               and st_dwithin(
                   c.geom,
                   st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography,
                   50
               )
        );
$$;

-- The size the answers add up to: the first answer decides an unknown bin,
-- after that the majority, a tie keeping what the map shows.
create or replace function container_kind_from_votes(p_current text, p_big bigint, p_small bigint, p_latest text)
returns text
language sql
immutable
as $$
    select case
        when p_current = 'unknown' then p_latest
        when p_big > p_small then 'big'
        when p_small > p_big then 'small'
        else p_current
    end;
$$;

create or replace function vote_container_size(
    p_container_id uuid,
    p_lon          double precision,
    p_lat          double precision,
    p_kind         text
)
returns table (
    container_id uuid,
    kind         text,
    verified     boolean,
    votes_big    bigint,
    votes_small  bigint,
    changed      boolean
)
language plpgsql
security definer
set search_path = public, extensions
as $$
#variable_conflict use_column
declare
    v_uid      uuid := require_user();
    v_point    geography;
    v_kind     text;
    v_new      text;
    v_distance double precision;
    v_big      bigint;
    v_small    bigint;
    v_verified boolean;
begin
    if p_kind is null or p_kind not in ('big', 'small') then
        raise exception 'bad kind' using errcode = 'KA012';
    end if;

    if p_lon is null or p_lat is null then
        raise exception 'too far to answer' using errcode = 'KA010';
    end if;
    v_point := st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography;

    -- Locked, so two people answering the same unknown bin at once cannot both
    -- read 'unknown' and both "decide" it.
    select c.kind, st_distance(c.geom, v_point)
      into v_kind, v_distance
      from containers c
     where c.id = p_container_id
       and c.deleted_at is null
       for update;

    if not found then
        raise exception 'container not found' using errcode = 'KA012';
    end if;

    if v_distance > 50 then
        raise exception 'too far to answer: % m', round(v_distance)
            using errcode = 'KA010', detail = round(v_distance)::text;
    end if;

    insert into container_size_votes (container_id, user_id, kind)
    values (p_container_id, v_uid, p_kind)
    on conflict (container_id, user_id)
    do update set kind = excluded.kind, updated_at = now();

    select count(*) filter (where kind = 'big'),
           count(*) filter (where kind = 'small')
      into v_big, v_small
      from container_size_votes
     where container_id = p_container_id;

    v_new := container_kind_from_votes(v_kind, v_big, v_small, p_kind);
    perform set_container_kind(p_container_id, v_new);

    -- Standing next to it and saying how big it is says it's there too (§4.6).
    if can_confirm_container_exists(p_container_id, p_lon, p_lat) then
        perform * from confirm_container_exists(p_container_id, p_lon, p_lat);
    end if;

    select c.verified into v_verified from containers c where c.id = p_container_id;

    return query select p_container_id, v_new, v_verified, v_big, v_small, v_new is distinct from v_kind;
end;
$$;

-- An admin's answer sets the size at once, from anywhere, and counts as their
-- answer from then on.
create or replace function admin_set_container_kind(p_id uuid, p_kind text)
returns boolean
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_uid uuid := require_admin();
begin
    if p_kind is null or p_kind not in ('big', 'small') then
        raise exception 'bad kind' using errcode = 'KA012';
    end if;

    if not exists (select 1 from containers where id = p_id and deleted_at is null) then
        raise exception 'container not found' using errcode = 'KA012';
    end if;

    insert into container_size_votes (container_id, user_id, kind)
    values (p_id, v_uid, p_kind)
    on conflict (container_id, user_id)
    do update set kind = excluded.kind, updated_at = now();

    perform set_container_kind(p_id, p_kind);
    return true;
end;
$$;

-- -----------------------------------------------------------------------------
-- 3. Reads gain the size answers. Return types change, so drop first (as 0015).
-- -----------------------------------------------------------------------------
drop function if exists container_detail(uuid, double precision, double precision);

create or replace function container_detail(
    p_container_id uuid,
    p_lon          double precision default null,
    p_lat          double precision default null
)
returns table (
    id                 uuid,
    code               text,
    kind               text,
    category           text,
    status             text,
    status_since       timestamptz,
    verified           boolean,
    municipality_id    smallint,
    municipality_name  text,
    lon                double precision,
    lat                double precision,
    open_reports       bigint,
    unconfirmed_full   bigint,
    added_by_me        boolean,
    i_confirmed        boolean,
    can_confirm_exists boolean,
    -- §4.6 bin size
    can_vote_size      boolean,
    my_size_vote       text,
    size_votes_big     bigint,
    size_votes_small   bigint
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    select
        c.id, c.code, c.kind, c.category, c.status, c.status_since, c.verified,
        c.municipality_id, m.name_mk,
        st_x(c.geom::geometry), st_y(c.geom::geometry),
        (select count(*) from reports r
          where r.container_id = c.id and r.state = 'open'),
        (select count(*) from reports r
          where r.container_id = c.id and r.state = 'open' and r.kind = 'full'),
        coalesce(c.added_by = auth.uid(), false),
        exists (
            select 1 from container_confirmations cc
             where cc.container_id = c.id and cc.user_id = auth.uid()
        ),
        can_confirm_container_exists(c.id, p_lon, p_lat),
        can_vote_container_size(c.id, p_lon, p_lat),
        (select v.kind from container_size_votes v
          where v.container_id = c.id and v.user_id = auth.uid()),
        (select count(*) from container_size_votes v
          where v.container_id = c.id and v.kind = 'big'),
        (select count(*) from container_size_votes v
          where v.container_id = c.id and v.kind = 'small')
    from containers c
    left join municipalities m on m.id = c.municipality_id
    where c.id = p_container_id
      and c.deleted_at is null;
$$;

-- "Map your street" lists the bins around you that still need someone: not yet
-- confirmed, or size not known yet.
drop function if exists unverified_nearby(double precision, double precision, int);

create or replace function unverified_nearby(
    p_lon   double precision,
    p_lat   double precision,
    p_max_m int default 150
)
returns table (
    id            uuid,
    code          text,
    kind          text,
    category      text,
    status        text,
    lon           double precision,
    lat           double precision,
    distance_m    double precision,
    i_confirmed   boolean,
    is_mine       boolean,
    can_confirm   boolean,
    verified      boolean,
    can_vote_size boolean,
    my_size_vote  text
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
        c.id, c.code, c.kind, c.category, c.status,
        st_x(c.geom::geometry), st_y(c.geom::geometry),
        st_distance(c.geom, o.g),
        exists (
            select 1 from container_confirmations cc
             where cc.container_id = c.id and cc.user_id = auth.uid()
        ),
        coalesce(c.added_by = auth.uid(), false),
        can_confirm_container_exists(c.id, p_lon, p_lat),
        c.verified,
        can_vote_container_size(c.id, p_lon, p_lat),
        (select v.kind from container_size_votes v
          where v.container_id = c.id and v.user_id = auth.uid())
    from containers c, origin o
    where c.deleted_at is null
      and (c.verified = false or c.kind = 'unknown')
      and st_dwithin(c.geom, o.g, p_max_m)
    order by c.geom <-> o.g;
$$;

-- §5.2 "nearest with space": a bin whose size nobody has given yet may well be
-- the big container the person needs, so it is offered too, nearest first. The
-- app draws it with the universal marker, so nobody mistakes it for a known one.
create or replace function nearest_containers(
    p_lon      double precision,
    p_lat      double precision,
    p_category text default 'general',
    p_limit    int default 10,
    p_max_m    int default 600,
    p_kind     text default 'big'
)
returns table (
    id         uuid,
    code       text,
    kind       text,
    category   text,
    status     text,
    verified   boolean,
    lon        double precision,
    lat        double precision,
    distance_m double precision
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
        c.id, c.code, c.kind, c.category, c.status, c.verified,
        st_x(c.geom::geometry), st_y(c.geom::geometry),
        st_distance(c.geom, o.g)
    from containers c, origin o
    where c.deleted_at is null
      and c.kind in (p_kind, 'unknown')
      and c.category = p_category
      and c.status not in ('full', 'destroyed', 'missing')
      and st_dwithin(c.geom, o.g, p_max_m)
    order by c.geom <-> o.g
    limit greatest(p_limit, 1);
$$;

-- add_container: as in 0015, except that a bin of unknown size counts as a
-- duplicate of either kind, and the adder's chosen size counts as their answer.
create or replace function add_container(
    p_lon        double precision,
    p_lat        double precision,
    p_kind       text,
    p_category   text,
    p_photo_path text,
    p_device_lon double precision,
    p_device_lat double precision,
    p_confirm_different boolean default false
)
returns table (
    container_id uuid,
    code         text,
    verified     boolean,
    remaining    int
)
language plpgsql
security definer
set search_path = public, extensions
as $$
#variable_conflict use_column
declare
    v_uid        uuid := require_user();
    v_admin      boolean := is_admin(v_uid);
    v_added      int;
    v_pin        geography := st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography;
    v_device     geography := st_setsrid(st_makepoint(p_device_lon, p_device_lat), 4326)::geography;
    v_distance   double precision;
    v_dupe_radius double precision;
    v_dupe       uuid;
    v_id         uuid;
    v_code       text;
begin
    if p_kind not in ('big', 'small') then
        raise exception 'bad kind' using errcode = 'KA012';
    end if;

    -- §4.6: the 2-container lifetime limit, admins exempt. Checked before any
    -- geometry work so a user at the limit gets the right message.
    select containers_added into v_added from profiles where id = v_uid;

    if not v_admin and coalesce(v_added, 0) >= 2 then
        raise exception 'add allowance used up' using errcode = 'KA004';
    end if;

    -- §4.6: GPS must be within 30 m of the pin.
    v_distance := st_distance(v_pin, v_device);
    if v_distance > 30 then
        raise exception 'too far from pin: % m', round(v_distance)
            using errcode = 'KA005', detail = round(v_distance)::text;
    end if;

    -- §4.6 duplicate check: same kind within 10 m (big) or 5 m (small). A bin of
    -- unknown size could be either, so it counts too.
    v_dupe_radius := case when p_kind = 'big' then 10 else 5 end;

    select c.id into v_dupe
      from containers c
     where c.deleted_at is null
       and c.kind in (p_kind, 'unknown')
       and st_dwithin(c.geom, v_pin, v_dupe_radius)
     order by c.geom <-> v_pin
     limit 1;

    if v_dupe is not null and not coalesce(p_confirm_different, false) then
        raise exception 'duplicate container nearby'
            using errcode = 'KA003', detail = v_dupe::text;
    end if;

    v_code := next_container_code();

    insert into containers (
        code, kind, category, geom, municipality_id,
        source, added_by, verified, status, status_since
    )
    values (
        v_code, p_kind, coalesce(p_category, 'general'), v_pin,
        municipality_at(p_lon, p_lat),
        'user', v_uid,
        -- §4.6: admins' containers are verified immediately.
        v_admin,
        'ok', now()
    )
    returning id into v_id;

    -- The adder stood next to it and chose its size: that is their answer.
    insert into container_size_votes (container_id, user_id, kind)
    values (v_id, v_uid, p_kind);

    -- The photo is kept as the evidence for this container's existence.
    insert into container_requests (
        user_id, geom, kind, category, photo_path, state, created_container_id, reviewed_at, evidence_only
    )
    values (
        v_uid, v_pin, p_kind, coalesce(p_category, 'general'), p_photo_path,
        'approved', v_id, now(), true
    );

    if not v_admin then
        update profiles
           set containers_added = containers_added + 1
         where id = v_uid;
    end if;

    return query
        select v_id, v_code, v_admin,
               case when v_admin then 999
                    else greatest(2 - (select containers_added from profiles where id = v_uid), 0)
               end;
end;
$$;

-- -----------------------------------------------------------------------------
-- Grants. Supabase's default privileges hand every NEW function to anon and
-- authenticated, so each one is revoked and re-granted explicitly, as in 0013.
-- -----------------------------------------------------------------------------
revoke execute on function
    containers_import_unknown_kind(),
    set_container_kind(uuid, text),
    can_vote_container_size(uuid, double precision, double precision),
    container_kind_from_votes(text, bigint, bigint, text),
    vote_container_size(uuid, double precision, double precision, text),
    admin_set_container_kind(uuid, text),
    container_detail(uuid, double precision, double precision),
    unverified_nearby(double precision, double precision, int)
from public, anon, authenticated;

-- Browsing stays anonymous (§2); can_vote_size is simply false for anon.
grant execute on function
    container_detail(uuid, double precision, double precision)
to anon, authenticated;

grant execute on function
    vote_container_size(uuid, double precision, double precision, text),
    unverified_nearby(double precision, double precision, int)
to authenticated;

-- Also checks role = 'admin' internally.
grant execute on function
    admin_set_container_kind(uuid, text)
to authenticated;

-- set_container_kind, can_vote_container_size, container_kind_from_votes and the
-- trigger function are internal: reachable only through the functions above.
