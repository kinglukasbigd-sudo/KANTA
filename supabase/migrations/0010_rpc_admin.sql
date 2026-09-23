-- =============================================================================
-- Kanta — 0010 admin RPCs
-- KANTA_SPEC.md §4.6 "Admin (Ivan)". Every function checks role = 'admin'.
-- =============================================================================
set search_path to public, extensions;

create or replace function require_admin()
returns uuid
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
declare
    v_uid uuid := require_user();
begin
    if not is_admin(v_uid) then
        raise exception 'admin only' using errcode = 'KA007';
    end if;
    return v_uid;
end;
$$;

-- The review queue (§4.6). Only pending requests that did not already become a
-- container (add_container writes an auto-approved row for its evidence photo).
create or replace function admin_pending_requests(p_limit int default 100)
returns table (
    id         uuid,
    lon        double precision,
    lat        double precision,
    kind       text,
    category   text,
    photo_path text,
    note       text,
    created_at timestamptz
)
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
begin
    perform require_admin();
    return query
        select
            r.id,
            st_x(r.geom::geometry), st_y(r.geom::geometry),
            r.kind, r.category, r.photo_path, r.note, r.created_at
        from container_requests r
        where r.state = 'pending'
        order by r.created_at asc
        limit greatest(p_limit, 1);
end;
$$;

-- Approve → create a VERIFIED container and link it. Reject → just close it.
create or replace function admin_review_request(p_id uuid, p_approve boolean)
returns table (
    request_id   uuid,
    state        text,
    container_id uuid
)
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_req   container_requests%rowtype;
    v_id    uuid;
    v_code  text;
begin
    perform require_admin();

    select * into v_req from container_requests where id = p_id and state = 'pending';
    if not found then
        raise exception 'request not found or already reviewed' using errcode = 'KA015';
    end if;

    if p_approve then
        v_code := next_container_code();

        insert into containers (
            code, kind, category, geom, municipality_id,
            source, added_by, verified, status, status_since
        )
        values (
            v_code, v_req.kind, v_req.category, v_req.geom,
            municipality_at(st_x(v_req.geom::geometry), st_y(v_req.geom::geometry)),
            'user', v_req.user_id, true, 'ok', now()
        )
        returning id into v_id;

        update container_requests
           set state = 'approved', created_container_id = v_id, reviewed_at = now()
         where id = p_id;

        -- An approved request is a correct observation: same +1 as a container
        -- verified by two neighbours (§4.6).
        if v_req.user_id is not null then
            update profiles set trust_score = trust_score + 1 where id = v_req.user_id;
        end if;
    else
        update container_requests
           set state = 'rejected', reviewed_at = now()
         where id = p_id;
    end if;

    return query
        select p_id,
               (select cr.state from container_requests cr where cr.id = p_id),
               v_id;
end;
$$;

create or replace function admin_unverified_containers(p_limit int default 200)
returns table (
    id                 uuid,
    code               text,
    kind               text,
    category           text,
    lon                double precision,
    lat                double precision,
    added_by           uuid,
    confirmation_count bigint,
    created_at         timestamptz
)
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
begin
    perform require_admin();
    return query
        select
            c.id, c.code, c.kind, c.category,
            st_x(c.geom::geometry), st_y(c.geom::geometry),
            c.added_by,
            (select count(*) from container_confirmations cc where cc.container_id = c.id),
            c.created_at
        from containers c
        where c.deleted_at is null
          and c.verified = false
        order by c.created_at asc
        limit greatest(p_limit, 1);
end;
$$;

create or replace function admin_verify_container(p_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_added_by uuid;
    v_verified boolean;
begin
    perform require_admin();

    select added_by, verified into v_added_by, v_verified
      from containers
     where id = p_id and deleted_at is null;

    if not found then
        raise exception 'container not found' using errcode = 'KA012';
    end if;

    if v_verified then
        return true;
    end if;

    update containers set verified = true where id = p_id;

    if v_added_by is not null then
        update profiles set trust_score = trust_score + 1 where id = v_added_by;
    end if;

    return true;
end;
$$;

-- Soft delete only (§4.6): history is kept, the map stops showing it.
create or replace function admin_delete_container(p_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, extensions
as $$
begin
    perform require_admin();

    update containers set deleted_at = now()
     where id = p_id and deleted_at is null;

    if not found then
        raise exception 'container not found' using errcode = 'KA012';
    end if;

    return true;
end;
$$;

-- §4.6 coverage map: where area checks happened, as GeoJSON, so Ivan can see
-- which parts of Skopje are still unmapped.
create or replace function admin_coverage(p_days int default 90)
returns jsonb
language plpgsql
stable
security definer
set search_path = public, extensions
as $$
declare
    v_result jsonb;
begin
    perform require_admin();

    select jsonb_build_object(
        'type', 'FeatureCollection',
        'features', coalesce(jsonb_agg(
            jsonb_build_object(
                'type', 'Feature',
                'geometry', st_asgeojson(a.geom)::jsonb,
                'properties', jsonb_build_object(
                    'radius_m', a.radius_m,
                    'result', a.result,
                    'created_at', a.created_at,
                    'age_days', extract(epoch from (now() - a.created_at)) / 86400.0
                )
            )
        ), '[]'::jsonb)
    )
    into v_result
    from area_checks a
    where a.created_at > now() - make_interval(days => p_days);

    return v_result;
end;
$$;
