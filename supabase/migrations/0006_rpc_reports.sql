-- =============================================================================
-- Kanta — 0006 report RPCs
-- KANTA_SPEC.md §4.3 (60 m rule, me-too dedupe), §6 (10 reports/user/day), §5.1
-- =============================================================================
set search_path to public, extensions;

-- Every write RPC starts here: no anonymous writes (§2 "Login required to
-- report, suggest or vote").
create or replace function require_user()
returns uuid
language plpgsql
stable
as $$
declare
    v_uid uuid := auth.uid();
begin
    if v_uid is null then
        raise exception 'not signed in' using errcode = 'KA011';
    end if;
    return v_uid;
end;
$$;

create or replace function is_admin(p_uid uuid default auth.uid())
returns boolean
language sql
stable
security definer
set search_path = public, extensions
as $$
    select coalesce((select role = 'admin' from profiles where id = p_uid), false);
$$;

-- -----------------------------------------------------------------------------
-- submit_report (§4.3)
--   * GPS must be within 60 m of the container
--   * <= 10 reports per user per day
--   * an open report of the same kind on the same container becomes a me-too
--   * recomputes container status afterwards
--
-- Returns which of the two happened so the app can say "Thanks — X neighbours
-- reported this too" instead of pretending it filed a new report.
-- -----------------------------------------------------------------------------
create or replace function submit_report(
    p_container_id uuid,
    p_kind         text,
    p_photo_path   text,
    p_note         text,
    p_lon          double precision,
    p_lat          double precision
)
returns table (
    report_id     uuid,
    merged_me_too boolean,
    me_too_count  bigint,
    status        text
)
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_uid       uuid := require_user();
    v_distance  double precision;
    v_existing  uuid;
    v_today     int;
    v_report_id uuid;
    v_merged    boolean := false;
begin
    -- Container must exist and not be soft-deleted (§4.6).
    select st_distance(c.geom, st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography)
      into v_distance
      from containers c
     where c.id = p_container_id
       and c.deleted_at is null;

    if v_distance is null then
        raise exception 'container not found' using errcode = 'KA012';
    end if;

    -- §4.3: "GPS must be within 60 m of the container".
    if v_distance > 60 then
        raise exception 'too far: % m', round(v_distance)
            using errcode = 'KA001', detail = round(v_distance)::text;
    end if;

    -- §6 rate limit, counted over a rolling 24h rather than calendar day so it
    -- cannot be reset by waiting for midnight.
    select count(*) into v_today
      from reports
     where user_id = v_uid
       and created_at > now() - interval '1 day';

    if v_today >= 10 then
        raise exception 'daily report limit reached' using errcode = 'KA002';
    end if;

    -- §4.3 dedupe: an open report of the same kind already exists here.
    select id into v_existing
      from reports
     where container_id = p_container_id
       and kind = p_kind
       and state = 'open'
     order by created_at asc
     limit 1;

    if v_existing is not null then
        -- The original reporter re-reporting is not a second voice; treat it as
        -- a no-op rather than letting one person satisfy the §5.1 threshold.
        if exists (select 1 from reports where id = v_existing and user_id = v_uid) then
            v_merged := true;
            v_report_id := v_existing;
        else
            insert into report_confirmations (report_id, user_id, kind, photo_path)
            values (v_existing, v_uid, 'me_too', p_photo_path)
            on conflict (report_id, user_id, kind) do nothing;

            v_merged := true;
            v_report_id := v_existing;
        end if;
    else
        insert into reports (container_id, user_id, kind, photo_path, note)
        values (p_container_id, v_uid, p_kind, p_photo_path, nullif(p_note, ''))
        returning id into v_report_id;
    end if;

    -- Triggers already recomputed; read the settled value back for the caller.
    return query
        select
            v_report_id,
            v_merged,
            (select count(*) from report_confirmations c
              where c.report_id = v_report_id and c.kind = 'me_too'),
            (select c.status from containers c where c.id = p_container_id);
end;
$$;

-- -----------------------------------------------------------------------------
-- confirm_report (§5.1)
--   kind = 'me_too'   → another voice on an open problem
--   kind = 'resolved' → with photo resolves immediately, without photo needs 2
-- -----------------------------------------------------------------------------
create or replace function confirm_report(
    p_report_id  uuid,
    p_kind       text,
    p_photo_path text default null
)
returns table (
    report_id    uuid,
    report_state text,
    status       text
)
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_uid       uuid := require_user();
    v_container uuid;
    v_state     text;
begin
    select r.container_id, r.state
      into v_container, v_state
      from reports r
      join containers c on c.id = r.container_id and c.deleted_at is null
     where r.id = p_report_id;

    if v_container is null then
        raise exception 'report not found' using errcode = 'KA013';
    end if;

    if v_state <> 'open' then
        raise exception 'report is no longer open' using errcode = 'KA013';
    end if;

    -- §5.1 counts "reporter + 1 me-too" as two voices. The reporter agreeing
    -- with themselves is still one voice, so it must not count as a second.
    -- (Resolving your own report is fine: you saw it emptied.)
    if p_kind = 'me_too'
       and exists (select 1 from reports where id = p_report_id and user_id = v_uid) then
        raise exception 'cannot confirm your own report' using errcode = 'KA008';
    end if;

    insert into report_confirmations (report_id, user_id, kind, photo_path)
    values (p_report_id, v_uid, p_kind, nullif(p_photo_path, ''))
    on conflict (report_id, user_id, kind) do nothing;

    if not found then
        raise exception 'already confirmed' using errcode = 'KA008';
    end if;

    return query
        select p_report_id,
               (select r.state from reports r where r.id = p_report_id),
               (select c.status from containers c where c.id = v_container);
end;
$$;

-- -----------------------------------------------------------------------------
-- §5.1 auto-expiry, run hourly by pg_cron (0011).
--   full  → expired after 24h with no confirmation
--   other → expired after 14 days with no confirmation
-- "No confirmation for 24h" is measured from the last activity on the report,
-- so a report that keeps attracting me-toos stays open.
-- -----------------------------------------------------------------------------
create or replace function expire_old_reports()
returns int
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_expired int;
    v_ids     uuid[];
begin
    with last_activity as (
        select
            r.id,
            r.container_id,
            r.kind,
            greatest(
                r.created_at,
                coalesce((select max(c.created_at) from report_confirmations c
                           where c.report_id = r.id), r.created_at)
            ) as touched_at
        from reports r
        where r.state = 'open'
    ),
    doomed as (
        select id, container_id
        from last_activity
        where (kind = 'full' and touched_at < now() - interval '24 hours')
           or (kind <> 'full' and touched_at < now() - interval '14 days')
    ),
    updated as (
        update reports r
           set state = 'expired'
          from doomed d
         where r.id = d.id
        returning d.container_id
    )
    select count(*), array_agg(distinct container_id)
      into v_expired, v_ids
      from updated;

    -- Statuses must settle after a bulk expiry, or the map keeps showing orange.
    if v_ids is not null then
        perform recompute_container_status(cid) from unnest(v_ids) as cid;
    end if;

    return coalesce(v_expired, 0);
end;
$$;
