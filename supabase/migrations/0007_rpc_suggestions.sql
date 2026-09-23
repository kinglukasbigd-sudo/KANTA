-- =============================================================================
-- Kanta — 0007 suggestion RPCs
-- KANTA_SPEC.md §4.4 (50 m merge), §5.3 (one vote per user, merge radius 50 m)
-- =============================================================================
set search_path to public, extensions;

-- Which municipality a point falls in (§7: assign with ST_Contains).
create or replace function municipality_at(p_lon double precision, p_lat double precision)
returns smallint
language sql
stable
security definer
set search_path = public, extensions
as $$
    select m.id
      from municipalities m
     where m.geom is not null
       and st_intersects(m.geom, st_setsrid(st_makepoint(p_lon, p_lat), 4326)::geography)
     limit 1;
$$;

-- -----------------------------------------------------------------------------
-- submit_suggestion (§4.4/§5.3)
-- An open suggestion within 50 m is merged into rather than duplicated: the
-- caller's vote is added to the existing one. `merged` lets the app say
-- "Voted for a suggestion that's already here" instead of claiming a new pin.
-- -----------------------------------------------------------------------------
create or replace function submit_suggestion(
    p_lon        double precision,
    p_lat        double precision,
    p_reason     text,
    p_note       text default null,
    p_photo_path text default null
)
returns table (
    suggestion_id uuid,
    merged        boolean,
    votes         int
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
    v_existing uuid;
    v_id       uuid;
    v_merged   boolean := false;
begin
    select s.id into v_existing
      from suggestions s
     where s.state = 'open'
       and st_dwithin(s.geom, v_point, 50)
     order by s.geom <-> v_point
     limit 1;

    if v_existing is not null then
        v_id := v_existing;
        v_merged := true;
        -- One vote per user (§5.3); re-submitting near your own pin is a no-op.
        insert into suggestion_votes (suggestion_id, user_id)
        values (v_id, v_uid)
        on conflict (suggestion_id, user_id) do nothing;
    else
        insert into suggestions (user_id, geom, reason, note, photo_path, municipality_id)
        values (v_uid, v_point, p_reason, nullif(p_note, ''), nullif(p_photo_path, ''),
                municipality_at(p_lon, p_lat))
        returning id into v_id;

        -- The author's own vote, so `votes` is always count(suggestion_votes)
        -- and the default of 1 in §6 stays true.
        insert into suggestion_votes (suggestion_id, user_id)
        values (v_id, v_uid)
        on conflict do nothing;
    end if;

    return query
        select v_id, v_merged, (select s.votes from suggestions s where s.id = v_id);
end;
$$;

-- One vote per user per suggestion (§5.3).
create or replace function vote_suggestion(p_suggestion_id uuid)
returns table (
    suggestion_id uuid,
    votes         int
)
language plpgsql
security definer
set search_path = public, extensions
as $$
    -- Names like container_id / state are both OUT columns of this function and
    -- table columns; inside the body they always mean the table column.
#variable_conflict use_column
declare
    v_uid uuid := require_user();
begin
    if not exists (select 1 from suggestions where id = p_suggestion_id and state = 'open') then
        raise exception 'suggestion not found' using errcode = 'KA014';
    end if;

    insert into suggestion_votes (suggestion_id, user_id)
    values (p_suggestion_id, v_uid)
    on conflict (suggestion_id, user_id) do nothing;

    if not found then
        raise exception 'already voted' using errcode = 'KA008';
    end if;

    return query
        select p_suggestion_id, (select s.votes from suggestions s where s.id = p_suggestion_id);
end;
$$;

-- Suggestions list, sorted by votes, optionally filtered by municipality (§4.5).
create or replace function suggestions_list(
    p_municipality_id smallint default null,
    p_limit           int default 50
)
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
    where (p_municipality_id is null or s.municipality_id = p_municipality_id)
    order by s.votes desc, s.created_at desc
    limit greatest(p_limit, 1);
$$;
