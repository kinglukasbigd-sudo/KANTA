-- =============================================================================
-- Kanta — 0009 "Map your street" RPCs
-- KANTA_SPEC.md §4.6
--
-- Every limit in this file is enforced HERE, in SQL, never only in the app:
--   * 30 m device-to-pin when adding
--   * duplicate radius 10 m (big) / 5 m (small), same kind
--   * 2 containers per account, lifetime — admins unlimited
--   * 3 container requests per user per day
--   * verification at 2 confirmations, and never by the adder
--   * 50 m to confirm a container exists
-- =============================================================================
set search_path to public, extensions;

-- Sequential human-readable code, SK-00001... (§7).
create sequence if not exists container_code_seq start 1;

create or replace function next_container_code()
returns text
language sql
volatile
as $$
    select 'SK-' || lpad(nextval('container_code_seq')::text, 5, '0');
$$;

-- Align the sequence with whatever the OSM import already created, so seeded
-- and user-added containers never collide on `code`.
create or replace function sync_container_code_seq()
returns void
language plpgsql
as $$
declare
    v_max bigint;
begin
    select coalesce(max(substring(code from 4)::bigint), 0) into v_max
      from containers
     where code ~ '^SK-[0-9]+$';
    perform setval('container_code_seq', greatest(v_max, 1), v_max > 0);
end;
$$;

-- -----------------------------------------------------------------------------
-- my_add_allowance (§4.6) — drives "You can add 2 containers" / "1 more".
-- -----------------------------------------------------------------------------
create or replace function my_add_allowance()
returns table (
    is_admin          boolean,
    containers_added  int,
    remaining         int
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    select
        p.role = 'admin',
        p.containers_added,
        case when p.role = 'admin' then 999
             else greatest(2 - p.containers_added, 0)
        end
    from profiles p
    where p.id = auth.uid();
$$;

-- -----------------------------------------------------------------------------
-- add_container (§4.6)
-- -----------------------------------------------------------------------------
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
    -- Names like container_id / state are both OUT columns of this function and
    -- table columns; inside the body they always mean the table column.
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
        user_id, geom, kind, category, photo_path, state, created_container_id, reviewed_at
    )
    values (
        v_uid, v_pin, p_kind, coalesce(p_category, 'general'), p_photo_path,
        'approved', v_id, now()
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
-- confirm_container_exists (§4.6)
--   * must be within 50 m
--   * the adder cannot confirm their own container
--   * at 2 confirmations the container is verified and the adder gains +1 trust
-- -----------------------------------------------------------------------------
create or replace function confirm_container_exists(
    p_container_id uuid,
    p_lon          double precision,
    p_lat          double precision
)
returns table (
    container_id      uuid,
    verified          boolean,
    confirmation_count bigint
)
language plpgsql
security definer
set search_path = public, extensions
as $$
    -- Names like container_id / state are both OUT columns of this function and
    -- table columns; inside the body they always mean the table column.
#variable_conflict use_column
declare
    v_uid      uuid := require_user();
    v_point    geography := st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography;
    v_added_by uuid;
    v_verified boolean;
    v_distance double precision;
    v_count    bigint;
begin
    select c.added_by, c.verified, st_distance(c.geom, v_point)
      into v_added_by, v_verified, v_distance
      from containers c
     where c.id = p_container_id
       and c.deleted_at is null;

    if not found then
        raise exception 'container not found' using errcode = 'KA012';
    end if;

    if v_added_by is not null and v_added_by = v_uid then
        raise exception 'cannot confirm your own container' using errcode = 'KA009';
    end if;

    if v_distance > 50 then
        raise exception 'too far to confirm: % m', round(v_distance)
            using errcode = 'KA010', detail = round(v_distance)::text;
    end if;

    insert into container_confirmations (container_id, user_id, kind)
    values (p_container_id, v_uid, 'exists')
    on conflict (container_id, user_id) do nothing;

    if not found then
        raise exception 'already confirmed' using errcode = 'KA008';
    end if;

    select count(*) into v_count
      from container_confirmations
     where container_id = p_container_id;

    -- §4.6: verified at 2 confirmations; the adder gains a trust point, once.
    if not v_verified and v_count >= 2 then
        update containers set verified = true where id = p_container_id;
        v_verified := true;

        if v_added_by is not null then
            update profiles set trust_score = trust_score + 1 where id = v_added_by;
        end if;
    end if;

    return query select p_container_id, v_verified, v_count;
end;
$$;

-- -----------------------------------------------------------------------------
-- submit_container_request (§4.6) — for users who used up their 2 adds.
-- Max 3 per user per day. Never appears on the map.
-- -----------------------------------------------------------------------------
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
    select count(*) into v_today
      from container_requests
     where user_id = v_uid
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
-- Area checks (§4.6)
-- -----------------------------------------------------------------------------
create or replace function submit_area_check(
    p_lon    double precision,
    p_lat    double precision,
    p_result text
)
returns uuid
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_uid uuid := require_user();
    v_id  uuid;
begin
    insert into area_checks (user_id, geom, radius_m, result)
    values (v_uid, st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography, 150, p_result)
    returning id into v_id;

    update profiles set last_area_check_at = now() where id = v_uid;

    return v_id;
end;
$$;

-- §4.6: prompt only if the user has no check in 30 days AND the area has no
-- recent check. "Recent" for an area is the same 90 days the coverage map uses,
-- so a neighbourhood someone already walked is not re-walked every month.
create or replace function should_prompt_area_check(
    p_lon double precision,
    p_lat double precision
)
returns boolean
language sql
stable
security definer
set search_path = public, extensions
as $$
    select
        auth.uid() is not null
        and coalesce(
            (select p.last_area_check_at < now() - interval '30 days'
               from profiles p where p.id = auth.uid()),
            true   -- never checked
        )
        and not exists (
            select 1
              from area_checks a
             where a.created_at > now() - interval '90 days'
               and st_dwithin(
                   a.geom,
                   st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography,
                   150
               )
        );
$$;

-- Unverified containers inside the check radius, so the sheet can offer
-- "Yes, it's here" in one tap (§4.6 step 3).
-- 0015 widens this function's result; dropping first keeps this file re-runnable
-- (create or replace cannot change a return type). 0015 re-creates its version.
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
    is_mine      boolean
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
        c.added_by is not distinct from auth.uid()
    from containers c, origin o
    where c.deleted_at is null
      and c.verified = false
      and st_dwithin(c.geom, o.g, p_max_m)
    order by c.geom <-> o.g;
$$;
