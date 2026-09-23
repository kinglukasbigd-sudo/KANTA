-- =============================================================================
-- Kanta — 0012 account deletion
-- KANTA_SPEC.md §8: "Delete account removes profile and anonymises reports."
-- Play Store requires this to be reachable from inside the app (§4.2).
-- =============================================================================
set search_path to public, extensions;

-- Anonymise rather than delete: the city's record of how long problems stayed
-- open must survive a user leaving, but nothing may still point at them.
--
-- reports.user_id, report_confirmations.user_id, suggestions.user_id,
-- container_requests.user_id and area_checks.user_id are all ON DELETE SET NULL,
-- so removing the auth user anonymises them in one step. containers.added_by is
-- likewise nulled, leaving the container on the map where it belongs.
--
-- suggestion_votes and container_confirmations are ON DELETE CASCADE: a vote and
-- a "yes it's here" are personal acts, not civic history, and a null-voter row
-- would corrupt the one-vote-per-user rule.
create or replace function delete_my_account()
returns boolean
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
    v_uid uuid := require_user();
begin
    -- Votes first, so the suggestions.votes trigger recounts before the user
    -- row disappears underneath it.
    delete from suggestion_votes       where user_id = v_uid;
    delete from container_confirmations where user_id = v_uid;

    -- Cascades to profiles and nulls every remaining reference.
    delete from auth.users where id = v_uid;

    return true;
end;
$$;

revoke all on function delete_my_account() from public, anon;
grant execute on function delete_my_account() to authenticated;
