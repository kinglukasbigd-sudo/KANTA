-- =============================================================================
-- Kanta — 0013 Row Level Security
-- KANTA_SPEC.md §6 RLS paragraph.
--
-- Shape of the model:
--   * anon + authenticated may SELECT public data (containers, municipalities,
--     suggestions, and reports only through the sanitised views)
--   * NOBODY may INSERT/UPDATE/DELETE the domain tables directly. Every write
--     goes through a SECURITY DEFINER RPC, which runs as the function owner and
--     is therefore unaffected by these policies.
--   * a user may read and update their OWN profile, but only the three columns
--     they are allowed to change. role / containers_added / last_area_check_at /
--     trust_score are enforced with column-level GRANTs, which the client cannot
--     talk its way around.
-- =============================================================================
set search_path to public, extensions;

alter table municipalities          enable row level security;
alter table profiles                enable row level security;
alter table containers              enable row level security;
alter table reports                 enable row level security;
alter table report_confirmations    enable row level security;
alter table suggestions             enable row level security;
alter table suggestion_votes        enable row level security;
alter table container_confirmations enable row level security;
alter table container_requests      enable row level security;
alter table area_checks             enable row level security;

-- -----------------------------------------------------------------------------
-- Baseline privileges. Start from nothing and grant back deliberately.
-- -----------------------------------------------------------------------------
revoke all on municipalities, profiles, containers, reports, report_confirmations,
              suggestions, suggestion_votes, container_confirmations,
              container_requests, area_checks
    from anon, authenticated;

grant select on municipalities to anon, authenticated;
grant select on containers     to anon, authenticated;
grant select on suggestions    to anon, authenticated;

-- The sanitised windows onto reports (§6: "without user_id exposed — use a view").
grant select on reports_public, suggestions_public, containers_public to anon, authenticated;

-- A user may read their own profile row and change only these three fields.
grant select                                      on profiles to authenticated;
grant update (display_name, municipality_id, lang) on profiles to authenticated;

-- -----------------------------------------------------------------------------
-- Policies
-- -----------------------------------------------------------------------------

-- Municipalities are public reference data.
drop policy if exists municipalities_read on municipalities;
create policy municipalities_read on municipalities
    for select to anon, authenticated using (true);

-- Containers: live rows only. Soft-deleted containers vanish for everyone; the
-- admin RPCs are SECURITY DEFINER and still see them.
drop policy if exists containers_read_live on containers;
create policy containers_read_live on containers
    for select to anon, authenticated using (deleted_at is null);

-- Suggestions are public (the view is what the app reads; this covers the table
-- for anything that joins it).
drop policy if exists suggestions_read on suggestions;
create policy suggestions_read on suggestions
    for select to anon, authenticated using (true);

-- Reports and their confirmations are NOT directly readable — identity leaks.
-- reports_public is the only way in. No SELECT policy is created on purpose.

-- Profiles: your own row only. Public screens show "a neighbour" (§8).
drop policy if exists profiles_read_own on profiles;
create policy profiles_read_own on profiles
    for select to authenticated using (id = auth.uid());

drop policy if exists profiles_update_own on profiles;
create policy profiles_update_own on profiles
    for update to authenticated
    using (id = auth.uid())
    with check (id = auth.uid());

-- Suggestion votes: a user may see their own votes (drives "you voted" state).
drop policy if exists suggestion_votes_read_own on suggestion_votes;
create policy suggestion_votes_read_own on suggestion_votes
    for select to authenticated using (user_id = auth.uid());
grant select on suggestion_votes to authenticated;

-- Container confirmations: same, for the "Yes, it's here" state (§4.6).
drop policy if exists container_confirmations_read_own on container_confirmations;
create policy container_confirmations_read_own on container_confirmations
    for select to authenticated using (user_id = auth.uid());
grant select on container_confirmations to authenticated;

-- §6: container_requests readable only by their author and by admins.
drop policy if exists container_requests_read_own_or_admin on container_requests;
create policy container_requests_read_own_or_admin on container_requests
    for select to authenticated
    using (user_id = auth.uid() or is_admin(auth.uid()));
grant select on container_requests to authenticated;

-- Area checks: a user may see their own history; the coverage map is admin-only
-- and goes through admin_coverage().
drop policy if exists area_checks_read_own on area_checks;
create policy area_checks_read_own on area_checks
    for select to authenticated using (user_id = auth.uid());
grant select on area_checks to authenticated;

-- -----------------------------------------------------------------------------
-- Function privileges.
--
-- RPCs are the only write path, so they are granted explicitly. Read-only RPCs
-- are open to anon because the map, container details and city stats are
-- browsable without an account (§2 Auth).
-- -----------------------------------------------------------------------------
revoke execute on all functions in schema public from public, anon, authenticated;

-- Anonymous browsing (§2).
grant execute on function
    containers_in_bbox(double precision, double precision, double precision, double precision),
    nearest_containers(double precision, double precision, text, int, int, text),
    nearest_container_to(double precision, double precision, int),
    container_detail(uuid),
    suggestions_list(smallint, int),
    municipality_stats(int)
to anon, authenticated;

-- Signed-in actions (§2: "Login required to report, suggest or vote").
grant execute on function
    submit_report(uuid, text, text, text, double precision, double precision),
    confirm_report(uuid, text, text),
    submit_suggestion(double precision, double precision, text, text, text),
    vote_suggestion(uuid),
    my_impact(),
    my_reports(int),
    my_resolved_since(timestamptz),
    add_container(double precision, double precision, text, text, text, double precision, double precision),
    my_add_allowance(),
    confirm_container_exists(uuid, double precision, double precision),
    submit_container_request(double precision, double precision, text, text, text, text),
    submit_area_check(double precision, double precision, text),
    should_prompt_area_check(double precision, double precision),
    unverified_nearby(double precision, double precision, int),
    delete_my_account()
to authenticated;

-- Admin RPCs. They also check role = 'admin' internally, so a leaked grant is
-- not sufficient to use them.
grant execute on function
    admin_pending_requests(int),
    admin_review_request(uuid, boolean),
    admin_unverified_containers(int),
    admin_verify_container(uuid),
    admin_delete_container(uuid),
    admin_coverage(int)
to authenticated;

-- Internal helpers stay unreachable from the client: recompute_container_status,
-- maybe_resolve_report, maybe_soft_delete_unverified, expire_old_reports,
-- next_container_code, require_user, require_admin, is_admin, municipality_at.
-- They are deliberately absent from the grants above.
