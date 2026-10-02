-- =============================================================================
-- Kanta — 0019 Fixing a container's kind (big container / small can)
-- KANTA_SPEC.md §4.6
--
-- Mapillary has one class for every trash can, from a street bin to a
-- 1,100-litre container, so tools/import_external brings all of them in as
-- small cans. Some are big containers, and until now nothing could change a
-- container's kind once it was on the map.
--
-- 1. "Yes, it's here — it's a big container". confirm_container_exists_as()
--    is the usual "Yes, it's here" plus the kind the person saw. When 2 people
--    say the other kind, the container changes to it — the same 2 that verify
--    a container.
--
-- 2. admin_set_container_kind() — an admin changes it in one tap.
--
-- confirm_container_exists() itself is unchanged, so the app already on
-- people's phones keeps working. Idempotent: safe to re-run.
-- =============================================================================
set search_path to public, extensions;

-- The kind the confirmer saw. NULL = a plain "Yes, it's here".
alter table container_confirmations add column if not exists seen_kind text;

alter table container_confirmations drop constraint if exists container_confirmations_seen_kind_check;
alter table container_confirmations add constraint container_confirmations_seen_kind_check
    check (seen_kind in ('big', 'small'));

-- A big container holds every category; a small can is only ever general (§4.6
-- asks for a category only for big ones).
create or replace function set_container_kind(p_id uuid, p_kind text)
returns void
language sql
security definer
set search_path = public, extensions
as $$
    update containers
       set kind     = p_kind,
           category = case when p_kind = 'small' then 'general' else category end
     where id = p_id
       and deleted_at is null
       and kind <> p_kind;
$$;

-- -----------------------------------------------------------------------------
-- 1. confirm_container_exists_as — "Yes, it's here", and it is a <p_kind>.
--    All of confirm_container_exists()'s rules apply (50 m, not your own, once
--    per person, verified at 2); it runs first and raises the same errors.
-- -----------------------------------------------------------------------------
create or replace function confirm_container_exists_as(
    p_container_id uuid,
    p_lon          double precision,
    p_lat          double precision,
    p_kind         text
)
returns table (
    container_id       uuid,
    verified           boolean,
    confirmation_count bigint,
    kind               text
)
language plpgsql
security definer
set search_path = public, extensions
as $$
#variable_conflict use_column
declare
    v_uid      uuid := require_user();
    v_verified boolean;
    v_count    bigint;
    v_votes    bigint;
    v_kind     text;
begin
    if p_kind is null or p_kind not in ('big', 'small') then
        raise exception 'bad kind' using errcode = 'KA012';
    end if;

    select r.verified, r.confirmation_count into v_verified, v_count
      from confirm_container_exists(p_container_id, p_lon, p_lat) r;

    update container_confirmations
       set seen_kind = p_kind
     where container_id = p_container_id
       and user_id = v_uid;

    select count(*) into v_votes
      from container_confirmations
     where container_id = p_container_id
       and seen_kind = p_kind;

    if v_votes >= 2 then
        perform set_container_kind(p_container_id, p_kind);
    end if;

    select c.kind into v_kind from containers c where c.id = p_container_id;

    return query select p_container_id, v_verified, v_count, v_kind;
end;
$$;

-- -----------------------------------------------------------------------------
-- 2. admin_set_container_kind — any container, verified or not.
-- -----------------------------------------------------------------------------
create or replace function admin_set_container_kind(p_id uuid, p_kind text)
returns boolean
language plpgsql
security definer
set search_path = public, extensions
as $$
begin
    perform require_admin();

    if p_kind is null or p_kind not in ('big', 'small') then
        raise exception 'bad kind' using errcode = 'KA012';
    end if;

    if not exists (select 1 from containers where id = p_id and deleted_at is null) then
        raise exception 'container not found' using errcode = 'KA012';
    end if;

    perform set_container_kind(p_id, p_kind);
    return true;
end;
$$;

-- -----------------------------------------------------------------------------
-- Grants. Supabase's default privileges hand every NEW function to anon and
-- authenticated, so each one is revoked and re-granted explicitly, as in 0013.
-- -----------------------------------------------------------------------------
revoke execute on function
    set_container_kind(uuid, text),
    confirm_container_exists_as(uuid, double precision, double precision, text),
    admin_set_container_kind(uuid, text)
from public, anon, authenticated;

grant execute on function
    confirm_container_exists_as(uuid, double precision, double precision, text)
to authenticated;

-- Also checks role = 'admin' internally.
grant execute on function
    admin_set_container_kind(uuid, text)
to authenticated;

-- set_container_kind is an internal helper: reachable only through the two
-- functions above, which run as their owner.
