-- =============================================================================
-- Kanta — 0008 stats RPCs
-- KANTA_SPEC.md §5.4 (impact counter, municipality ranking, Fixed! worker)
-- =============================================================================
set search_path to public, extensions;

-- §5.4 impact counter. Only reports that actually got RESOLVED count, and a
-- me-too counts as much as the original report: both helped.
create or replace function my_impact()
returns table (
    emptied            bigint,
    repaired           bigint,
    containers_placed  bigint
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    with mine as (
        select r.id, r.kind
          from reports r
         where r.state = 'resolved'
           and (
               r.user_id = auth.uid()
               or exists (
                   select 1 from report_confirmations c
                    where c.report_id = r.id
                      and c.user_id = auth.uid()
                      and c.kind = 'me_too'
               )
           )
    )
    select
        (select count(*) from mine where kind = 'full'),
        (select count(*) from mine where kind in ('damaged', 'burning', 'destroyed')),
        -- A suggestion the user created or voted for that became a real container.
        (select count(*)
           from suggestions s
          where s.state = 'placed'
            and exists (
                select 1 from suggestion_votes v
                 where v.suggestion_id = s.id and v.user_id = auth.uid()
            ));
$$;

-- §5.4 municipality ranking: median hours-to-resolve over the window, open
-- report count, active reporters, plus the same median for the PREVIOUS window
-- so the client can draw the trend arrow. Neutral data, no judgement.
create or replace function municipality_stats(p_days int default 30)
returns table (
    municipality_id     smallint,
    name_mk             text,
    name_sq             text,
    name_en             text,
    median_hours        double precision,
    resolved_count      bigint,
    open_count          bigint,
    active_reporters    bigint,
    prev_median_hours   double precision
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    with bounds as (
        select
            now() - make_interval(days => p_days)     as cur_from,
            now() - make_interval(days => p_days * 2) as prev_from,
            now() - make_interval(days => p_days)     as prev_to
    ),
    joined as (
        select r.*, c.municipality_id
          from reports r
          join containers c on c.id = r.container_id
         where c.deleted_at is null
    ),
    cur as (
        select
            j.municipality_id,
            percentile_cont(0.5) within group (
                order by extract(epoch from (j.resolved_at - j.created_at)) / 3600.0
            ) filter (where j.state = 'resolved')                     as median_hours,
            count(*) filter (where j.state = 'resolved')              as resolved_count,
            count(distinct j.user_id)                                 as active_reporters
        from joined j, bounds b
        where j.created_at >= b.cur_from
        group by j.municipality_id
    ),
    prev as (
        select
            j.municipality_id,
            percentile_cont(0.5) within group (
                order by extract(epoch from (j.resolved_at - j.created_at)) / 3600.0
            ) filter (where j.state = 'resolved')                     as median_hours
        from joined j, bounds b
        where j.created_at >= b.prev_from and j.created_at < b.prev_to
        group by j.municipality_id
    ),
    still_open as (
        select j.municipality_id, count(*) as open_count
          from joined j
         where j.state = 'open'
         group by j.municipality_id
    )
    select
        m.id, m.name_mk, m.name_sq, m.name_en,
        cur.median_hours,
        coalesce(cur.resolved_count, 0),
        coalesce(still_open.open_count, 0),
        coalesce(cur.active_reporters, 0),
        prev.median_hours
    from municipalities m
    left join cur        on cur.municipality_id = m.id
    left join prev       on prev.municipality_id = m.id
    left join still_open on still_open.municipality_id = m.id
    -- Municipalities with no resolved reports yet sort last rather than first.
    order by cur.median_hours asc nulls last, m.name_en;
$$;

-- §5.4 "Fixed!" worker: reports I filed or confirmed that resolved since `ts`.
create or replace function my_resolved_since(p_since timestamptz)
returns table (
    report_id        uuid,
    container_id     uuid,
    container_code   text,
    kind             text,
    resolved_at      timestamptz,
    hours_to_resolve double precision,
    before_photo     text,
    after_photo      text
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    select
        r.id, r.container_id, c.code, r.kind, r.resolved_at,
        extract(epoch from (r.resolved_at - r.created_at)) / 3600.0,
        r.photo_path,
        -- The resolving confirmation's photo, when someone supplied one (§5.4).
        (select rc.photo_path
           from report_confirmations rc
          where rc.report_id = r.id
            and rc.kind = 'resolved'
            and rc.photo_path is not null
          order by rc.created_at desc
          limit 1)
    from reports r
    join containers c on c.id = r.container_id
    where r.state = 'resolved'
      and r.resolved_at > p_since
      and c.deleted_at is null
      and (
          r.user_id = auth.uid()
          or exists (
              select 1 from report_confirmations rc2
               where rc2.report_id = r.id and rc2.user_id = auth.uid()
          )
      )
    order by r.resolved_at desc;
$$;

-- The list behind "My reports & profile" (§4.5 screen 11).
create or replace function my_reports(p_limit int default 100)
returns table (
    report_id      uuid,
    container_id   uuid,
    container_code text,
    kind           text,
    state          text,
    photo_path     text,
    note           text,
    created_at     timestamptz,
    resolved_at    timestamptz,
    me_too_count   bigint
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    select
        r.id, r.container_id, c.code, r.kind, r.state, r.photo_path, r.note,
        r.created_at, r.resolved_at,
        (select count(*) from report_confirmations rc
          where rc.report_id = r.id and rc.kind = 'me_too')
    from reports r
    join containers c on c.id = r.container_id
    where r.user_id = auth.uid()
    order by r.created_at desc
    limit greatest(p_limit, 1);
$$;
