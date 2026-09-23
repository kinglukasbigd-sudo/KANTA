-- =============================================================================
-- Kanta — 0015 "Map your street" fixes
-- KANTA_SPEC.md §4.6
--
-- 1. "If 2 users report an unverified container as missing, it is removed from
--    the map." submit_report turns the second 'missing' report into a me-too on
--    the first (§4.3 dedupe), but maybe_soft_delete_unverified() only counted
--    distinct REPORTERS and was never called for confirmations — so the second
--    person could never trigger the removal. It now counts reporters and me-too
--    confirmers together, and the confirmations trigger calls it too.
--
-- 2. "Yes, it's here" is offered only where the server would accept it (§4.6:
--    within 50 m, never for your own container, once per person). The app does
--    not re-implement that rule; it asks. container_detail() and
--    unverified_nearby() now return can_confirm_exists / can_confirm, computed
--    here from the same 50 m and adder checks confirm_container_exists() uses.
--
-- 3. The 3-requests-a-day limit no longer counts add_container's evidence rows
--    (see section 3 below).
--
-- 4. Seven RPCs failed on ambiguous column names ("Yes, it's here", admin
--    approve/reject, me-too, suggestions) — see section 4 at the end.
-- =============================================================================
set search_path to public, extensions;

-- The one definition of "may this user say it exists", shared by both reads
-- below and matching confirm_container_exists() in 0009.
create or replace function can_confirm_container_exists(
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
               and c.verified = false
               and c.added_by is distinct from auth.uid()
               and st_dwithin(
                   c.geom,
                   st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography,
                   50
               )
        )
        and not exists (
            select 1 from container_confirmations cc
             where cc.container_id = p_container_id
               and cc.user_id = auth.uid()
        );
$$;

-- -----------------------------------------------------------------------------
-- 1. Missing-report removal counts every distinct person who said "missing".
-- -----------------------------------------------------------------------------
create or replace function maybe_soft_delete_unverified(p_container_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_verified   boolean;
    v_added_by   uuid;
    v_deleted_at timestamptz;
    v_missing    int;
begin
    select verified, added_by, deleted_at
      into v_verified, v_added_by, v_deleted_at
      from containers
     where id = p_container_id;

    if not found or v_verified or v_deleted_at is not null then
        return false;
    end if;

    -- Reporters of open 'missing' reports, plus everyone who said "me too" on
    -- one. The adder never counts: they cannot make their own container vanish
    -- for a trust point's worth of mischief in either direction.
    select count(distinct who)
      into v_missing
      from (
          select r.user_id as who
            from reports r
           where r.container_id = p_container_id
             and r.kind = 'missing'
             and r.state = 'open'
          union
          select c.user_id
            from report_confirmations c
            join reports r on r.id = c.report_id
           where r.container_id = p_container_id
             and r.kind = 'missing'
             and r.state = 'open'
             and c.kind = 'me_too'
      ) people
     where who is not null
       and who is distinct from v_added_by;

    if v_missing >= 2 then
        update containers set deleted_at = now() where id = p_container_id;

        if v_added_by is not null then
            update profiles
               set trust_score = trust_score - 1
             where id = v_added_by;
        end if;

        return true;
    end if;

    return false;
end;
$$;

create or replace function trg_confirmations_touch_status()
returns trigger
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_report_id uuid := coalesce(new.report_id, old.report_id);
    v_container uuid;
begin
    -- A 'resolved' confirmation may close the report, which in turn changes the
    -- container's status, so resolution is evaluated before the recompute.
    perform maybe_resolve_report(v_report_id);

    select container_id into v_container from reports where id = v_report_id;
    if v_container is not null then
        perform recompute_container_status(v_container);
        -- A me-too on a 'missing' report is the second voice §4.6 waits for.
        perform maybe_soft_delete_unverified(v_container);
    end if;

    return coalesce(new, old);
end;
$$;

-- -----------------------------------------------------------------------------
-- 2a. container_detail gains the caller's relationship to the container.
--     Optional p_lon/p_lat: without them can_confirm_exists is simply false.
--     The return type changes, so the old signature is dropped first.
-- -----------------------------------------------------------------------------
drop function if exists container_detail(uuid);

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
    -- §5.1: "1 person says it's full" before the marker turns orange.
    unconfirmed_full   bigint,
    -- §4.6: the caller's own container ("Added by you") and their confirmation.
    added_by_me        boolean,
    i_confirmed        boolean,
    can_confirm_exists boolean
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
        can_confirm_container_exists(c.id, p_lon, p_lat)
    from containers c
    left join municipalities m on m.id = c.municipality_id
    where c.id = p_container_id
      and c.deleted_at is null;
$$;

-- -----------------------------------------------------------------------------
-- 2b. unverified_nearby gains can_confirm. The caller's position is the check
--     centre, which is also where they are standing.
-- -----------------------------------------------------------------------------
drop function if exists unverified_nearby(double precision, double precision, int);

create or replace function unverified_nearby(
    p_lon   double precision,
    p_lat   double precision,
    p_max_m int default 150
)
returns table (
    id           uuid,
    code         text,
    kind         text,
    category     text,
    status       text,
    lon          double precision,
    lat          double precision,
    distance_m   double precision,
    i_confirmed  boolean,
    is_mine      boolean,
    can_confirm  boolean
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
        -- auth.uid() is never null here (authenticated only), so an OSM row with
        -- added_by = null is never "mine".
        coalesce(c.added_by = auth.uid(), false),
        can_confirm_container_exists(c.id, p_lon, p_lat)
    from containers c, origin o
    where c.deleted_at is null
      and c.verified = false
      and st_dwithin(c.geom, o.g, p_max_m)
    order by c.geom <-> o.g;
$$;

-- -----------------------------------------------------------------------------
-- Grants. Supabase's default privileges hand every NEW function to anon and
-- authenticated, so each one is revoked and re-granted explicitly, exactly as
-- 0013 does.
-- -----------------------------------------------------------------------------
revoke execute on function
    can_confirm_container_exists(uuid, double precision, double precision),
    container_detail(uuid, double precision, double precision),
    unverified_nearby(double precision, double precision, int)
from public, anon, authenticated;

-- Browsing stays anonymous (§2); can_confirm_exists is simply false for anon.
grant execute on function
    container_detail(uuid, double precision, double precision)
to anon, authenticated;

grant execute on function
    unverified_nearby(double precision, double precision, int)
to authenticated;

-- can_confirm_container_exists is an internal helper: reachable only through
-- the two functions above, which run as their owner.

-- -----------------------------------------------------------------------------
-- 3. "Max 3 requests per user per day" counted every container_requests row,
--    including the evidence row add_container() writes for each successful add
--    (state 'approved', holding the photo). A user who had just used their two
--    adds could therefore send only ONE request that day instead of three.
--    Evidence rows are now flagged and the daily count skips them.
-- -----------------------------------------------------------------------------
alter table container_requests
    add column if not exists evidence_only boolean not null default false;

-- Rows written before this migration by add_container: approved at the very
-- moment they were created, which only add_container does.
update container_requests
   set evidence_only = true
 where state = 'approved'
   and created_container_id is not null
   and reviewed_at = created_at
   and not evidence_only;

-- add_container: unchanged from 0009 except that its evidence row is flagged.
create or replace function add_container(
    p_lon        double precision,
    p_lat        double precision,
    p_kind       text,
    p_category   text,
    p_photo_path text,
    p_device_lon double precision,
    p_device_lat double precision,
    -- §4.6: on a duplicate the app asks "Is it this one?". If the user answers
    -- "no, it's a different one", the call is repeated with this set. The 30 m
    -- rule and the 2-container limit still apply; only the duplicate check yields.
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

    -- §4.6 duplicate check: same kind within 10 m (big) or 5 m (small).
    v_dupe_radius := case when p_kind = 'big' then 10 else 5 end;

    select c.id into v_dupe
      from containers c
     where c.deleted_at is null
       and c.kind = p_kind
       and st_dwithin(c.geom, v_pin, v_dupe_radius)
     order by c.geom <-> v_pin
     limit 1;

    if v_dupe is not null and not coalesce(p_confirm_different, false) then
        -- The app shows "Is it this one?" with this container. The user either
        -- reports on the existing one, or confirms theirs is different and the
        -- call comes back with p_confirm_different = true.
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

create or replace function submit_container_request(
    p_lon        double precision,
    p_lat        double precision,
    p_kind       text,
    p_category   text,
    p_photo_path text,
    p_note       text default null
)
returns table (
    request_id      uuid,
    remaining_today int
)
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_uid   uuid := require_user();
    v_today int;
    v_id    uuid;
begin
    if p_kind not in ('big', 'small') then
        raise exception 'bad kind' using errcode = 'KA012';
    end if;

    -- Only real requests count toward the 3 a day, not add_container's evidence.
    select count(*) into v_today
      from container_requests
     where user_id = v_uid
       and not evidence_only
       and created_at > now() - interval '1 day';

    if v_today >= 3 then
        raise exception 'daily request limit reached' using errcode = 'KA006';
    end if;

    insert into container_requests (user_id, geom, kind, category, photo_path, note)
    values (
        v_uid, st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography,
        p_kind, coalesce(p_category, 'general'), p_photo_path, nullif(p_note, '')
    )
    returning id into v_id;

    return query select v_id, greatest(3 - (v_today + 1), 0);
end;
$$;

-- -----------------------------------------------------------------------------
-- 4. Name clashes between OUT columns and table columns.
--
--    A plpgsql function declared `returns table (container_id uuid, …)` has a
--    variable called container_id, so `on conflict (container_id, user_id)` or
--    `where state = 'pending'` inside it is ambiguous and Postgres refuses to
--    run the statement. This made "Yes, it's here" fail every time, and the
--    admin approve/reject too.
--
--    Fixed at the source: every affected function now starts with
--    `#variable_conflict use_column` (0006 submit_report, confirm_report;
--    0007 submit_suggestion, vote_suggestion; 0009 add_container,
--    confirm_container_exists; 0010 admin_review_request; and add_container
--    above). Supabase does not allow `alter function … set
--    plpgsql.variable_conflict`, so a database created before this fix must
--    re-run 0006, 0007, 0009 and 0010, then this file (see supabase/README.md).
-- -----------------------------------------------------------------------------
