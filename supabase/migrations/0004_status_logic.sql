-- =============================================================================
-- Kanta — 0004 container status engine
-- KANTA_SPEC.md §5.1, implemented exactly.
--
--   ok        — no open reports
--   full      — open `full` report with >= 2 distinct confirmations
--               (reporter + 1 me-too) within 6h, OR 1 report by a trusted user
--               (trust_score >= 5)
--   broken    — open `damaged` or `burning` report
--   destroyed — open `destroyed` report confirmed by >= 2 users
--   missing   — open `missing` report confirmed by >= 2 users
--
--   Priority: destroyed > missing > broken > full > ok
--
-- Two readings of §5.1 had to be pinned down; both are flagged in KANTA_SPEC
-- review notes:
--   (a) "within 6h" is read as "the me-too must arrive within 6h OF THE REPORT",
--       not "in the last 6 hours". A container that has been confirmed full does
--       not silently un-full itself after six hours — it stays full until the
--       report resolves or expires at 24h.
--   (b) "confirmed by >= 2 users" for destroyed/missing is read as 2 me-too
--       CONFIRMATIONS (so 3 distinct people including the reporter). The `full`
--       rule spells out "reporter + 1 me-too" for its count of 2; the different
--       wording here is taken to mean a genuinely higher bar for the two
--       statuses that erase a container from the map.
-- =============================================================================
set search_path to public, extensions;

-- Rank used for the §5.1 priority comparison. Higher wins.
create or replace function status_rank(p_status text)
returns int
language sql
immutable
as $$
    select case p_status
        when 'destroyed' then 5
        when 'missing'   then 4
        when 'broken'    then 3
        when 'full'      then 2
        else 0                    -- 'ok'
    end;
$$;

-- -----------------------------------------------------------------------------
-- The single source of truth for a container's status. Never set by the client.
-- -----------------------------------------------------------------------------
create or replace function recompute_container_status(p_container_id uuid)
returns text
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_new_status text := 'ok';
    v_current    text;
    v_exists     boolean;
begin
    select status into v_current
      from containers
     where id = p_container_id;

    if not found then
        return null;
    end if;

    -- destroyed: open 'destroyed' report with >= 2 me-too confirmations.
    select exists (
        select 1
          from reports r
         where r.container_id = p_container_id
           and r.state = 'open'
           and r.kind = 'destroyed'
           and (
               select count(distinct c.user_id)
                 from report_confirmations c
                where c.report_id = r.id and c.kind = 'me_too'
                  and c.user_id is distinct from r.user_id
           ) >= 2
    ) into v_exists;
    if v_exists then
        v_new_status := 'destroyed';
    end if;

    -- missing: same bar as destroyed.
    if status_rank(v_new_status) < status_rank('missing') then
        select exists (
            select 1
              from reports r
             where r.container_id = p_container_id
               and r.state = 'open'
               and r.kind = 'missing'
               and (
                   select count(distinct c.user_id)
                     from report_confirmations c
                    where c.report_id = r.id and c.kind = 'me_too'
                  and c.user_id is distinct from r.user_id
               ) >= 2
        ) into v_exists;
        if v_exists then
            v_new_status := 'missing';
        end if;
    end if;

    -- broken: a single open 'damaged' or 'burning' report is enough.
    if status_rank(v_new_status) < status_rank('broken') then
        select exists (
            select 1
              from reports r
             where r.container_id = p_container_id
               and r.state = 'open'
               and r.kind in ('damaged', 'burning')
        ) into v_exists;
        if v_exists then
            v_new_status := 'broken';
        end if;
    end if;

    -- full: (reporter + >= 1 me-too within 6h of the report) OR a trusted reporter.
    if status_rank(v_new_status) < status_rank('full') then
        select exists (
            select 1
              from reports r
              left join profiles p on p.id = r.user_id
             where r.container_id = p_container_id
               and r.state = 'open'
               and r.kind = 'full'
               and (
                   -- 1 + distinct me-too users >= 2, i.e. at least one me-too,
                   -- and it landed within 6h of the report being filed.
                   (
                       select count(distinct c.user_id)
                         from report_confirmations c
                        where c.report_id = r.id
                          and c.kind = 'me_too'
                          and c.user_id is distinct from r.user_id
                          and c.created_at <= r.created_at + interval '6 hours'
                   ) + 1 >= 2
                   -- ...or one report from a trusted user (§5.1).
                   or coalesce(p.trust_score, 0) >= 5
               )
        ) into v_exists;
        if v_exists then
            v_new_status := 'full';
        end if;
    end if;

    -- status_since only moves when the status actually changes, so "full for 31 h"
    -- (§4.1) measures the problem, not the last time a row was touched.
    if v_new_status is distinct from v_current then
        update containers
           set status = v_new_status,
               status_since = now()
         where id = p_container_id;
    end if;

    return v_new_status;
end;
$$;

-- -----------------------------------------------------------------------------
-- Resolution (§5.1): a 'resolved' confirmation WITH a photo from any user, or
-- 2 without a photo, closes the report.
-- -----------------------------------------------------------------------------
create or replace function maybe_resolve_report(p_report_id uuid)
returns boolean
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_with_photo    int;
    v_without_photo int;
    v_state         text;
begin
    select state into v_state from reports where id = p_report_id;
    if v_state is distinct from 'open' then
        return false;
    end if;

    select
        count(*) filter (where photo_path is not null),
        count(distinct user_id) filter (where photo_path is null)
      into v_with_photo, v_without_photo
      from report_confirmations
     where report_id = p_report_id
       and kind = 'resolved';

    if v_with_photo >= 1 or v_without_photo >= 2 then
        update reports
           set state = 'resolved',
               resolved_at = now()
         where id = p_report_id;
        return true;
    end if;

    return false;
end;
$$;

-- -----------------------------------------------------------------------------
-- §4.6: two 'missing' reports on an UNVERIFIED container soft-delete it and
-- cost the adder a trust point. Verified containers are never auto-removed.
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

    select count(distinct user_id)
      into v_missing
      from reports
     where container_id = p_container_id
       and kind = 'missing'
       and state = 'open';

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

-- -----------------------------------------------------------------------------
-- Triggers: any change to reports or their confirmations recomputes the status.
-- -----------------------------------------------------------------------------
create or replace function trg_reports_touch_status()
returns trigger
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_container uuid := coalesce(new.container_id, old.container_id);
begin
    perform recompute_container_status(v_container);
    perform maybe_soft_delete_unverified(v_container);
    return coalesce(new, old);
end;
$$;

drop trigger if exists reports_touch_status on reports;
create trigger reports_touch_status
    after insert or update or delete on reports
    for each row execute function trg_reports_touch_status();

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
    end if;

    return coalesce(new, old);
end;
$$;

drop trigger if exists confirmations_touch_status on report_confirmations;
create trigger confirmations_touch_status
    after insert or update or delete on report_confirmations
    for each row execute function trg_confirmations_touch_status();

-- -----------------------------------------------------------------------------
-- suggestions.votes is always count(suggestion_votes) (§5.3, one vote per user).
-- -----------------------------------------------------------------------------
create or replace function trg_recount_suggestion_votes()
returns trigger
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_suggestion uuid := coalesce(new.suggestion_id, old.suggestion_id);
begin
    update suggestions
       set votes = (select count(*) from suggestion_votes where suggestion_id = v_suggestion)
     where id = v_suggestion;
    return coalesce(new, old);
end;
$$;

drop trigger if exists suggestion_votes_recount on suggestion_votes;
create trigger suggestion_votes_recount
    after insert or delete on suggestion_votes
    for each row execute function trg_recount_suggestion_votes();
