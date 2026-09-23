-- =============================================================================
-- Kanta — 0005 map read RPCs
-- KANTA_SPEC.md §6, §5.2
-- =============================================================================
set search_path to public, extensions;

-- Lightweight rows for the map (§6). Excludes soft-deleted containers; returns
-- `verified` so the client can pick the dashed unverified marker (§3.4/§4.6).
create or replace function containers_in_bbox(
    min_lon double precision,
    min_lat double precision,
    max_lon double precision,
    max_lat double precision
)
returns table (
    id       uuid,
    code     text,
    kind     text,
    category text,
    status   text,
    verified boolean,
    lon      double precision,
    lat      double precision
)
language sql
stable
security definer
set search_path = public, extensions
as $$
    select
        c.id, c.code, c.kind, c.category, c.status, c.verified,
        st_x(c.geom::geometry), st_y(c.geom::geometry)
    from containers c
    where c.deleted_at is null
      and c.geom && st_makeenvelope(min_lon, min_lat, max_lon, max_lat, 4326)::geography
    -- The map draws at most this many at once; clustering (§3.4) handles the rest.
    limit 20000;
$$;

-- §5.2: nearest containers of the SAME category whose status is not
-- full/destroyed/missing, max 600 m, nearest first.
--
-- Small cans may substitute for nothing — only big containers are offered for
-- bags — so a 'big' query never returns 'small'.
create or replace function nearest_containers(
    p_lon      double precision,
    p_lat      double precision,
    p_category text default 'general',
    p_limit    int default 10,
    p_max_m    int default 600,
    p_kind     text default 'big'
)
returns table (
    id         uuid,
    code       text,
    kind       text,
    category   text,
    status     text,
    verified   boolean,
    lon        double precision,
    lat        double precision,
    distance_m double precision
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
        c.id, c.code, c.kind, c.category, c.status, c.verified,
        st_x(c.geom::geometry), st_y(c.geom::geometry),
        st_distance(c.geom, o.g)
    from containers c, origin o
    where c.deleted_at is null
      and c.kind = p_kind
      and c.category = p_category
      and c.status not in ('full', 'destroyed', 'missing')
      and st_dwithin(c.geom, o.g, p_max_m)
    -- KNN operator on the GIST index, then exact distance for the ordering.
    order by c.geom <-> o.g
    limit greatest(p_limit, 1);
$$;

-- For snapping a new report to the container the user is standing at (§4.3).
create or replace function nearest_container_to(
    p_lon   double precision,
    p_lat   double precision,
    p_max_m int default 60
)
returns table (
    id         uuid,
    code       text,
    kind       text,
    category   text,
    status     text,
    verified   boolean,
    lon        double precision,
    lat        double precision,
    distance_m double precision
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
        c.id, c.code, c.kind, c.category, c.status, c.verified,
        st_x(c.geom::geometry), st_y(c.geom::geometry),
        st_distance(c.geom, o.g)
    from containers c, origin o
    where c.deleted_at is null
      and st_dwithin(c.geom, o.g, p_max_m)
    order by c.geom <-> o.g
    limit 1;
$$;

-- Everything a container detail sheet needs in one call (§4.1).
create or replace function container_detail(p_container_id uuid)
returns table (
    id                uuid,
    code              text,
    kind              text,
    category          text,
    status            text,
    status_since      timestamptz,
    verified          boolean,
    municipality_id   smallint,
    municipality_name text,
    lon               double precision,
    lat               double precision,
    open_reports      bigint,
    -- §5.1: "1 person says it's full" before the marker turns orange.
    unconfirmed_full  bigint
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
          where r.container_id = c.id and r.state = 'open' and r.kind = 'full')
    from containers c
    left join municipalities m on m.id = c.municipality_id
    where c.id = p_container_id
      and c.deleted_at is null;
$$;
