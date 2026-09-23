-- =============================================================================
-- Kanta — 0002 tables
-- KANTA_SPEC.md §6 (data model) + §4.6 (Map your street)
-- =============================================================================
set search_path to public, extensions;

-- -----------------------------------------------------------------------------
-- Error codes raised by the RPCs.
--
-- Every rule the app must explain in the user's language is signalled with a
-- stable SQLSTATE rather than a message string, so the Kotlin layer maps codes
-- to localised text and the SQL never carries user-facing copy (§8: all strings
-- in strings.xml, three languages).
--
--   KA001 too far from container (> 60 m, §4.3)
--   KA002 daily report limit reached (10/day, §6)
--   KA003 a container of the same kind is already here (10 m / 5 m, §4.6)
--   KA004 container add allowance used up (2 lifetime, §4.6)
--   KA005 too far from the pin to add (> 30 m, §4.6)
--   KA006 daily container-request limit reached (3/day, §4.6)
--   KA007 admin only
--   KA008 already done (duplicate confirmation / vote)
--   KA009 cannot confirm your own container (§4.6)
--   KA010 too far to confirm (> 50 m, §4.6)
--   KA011 not signed in
--   KA012 container not found, or soft-deleted
--   KA013 report not found, or no longer open
--   KA014 suggestion not found
--   KA015 container request not found, or already reviewed
-- -----------------------------------------------------------------------------

-- 10 municipalities of Skopje (§5.4). Geometry is loaded in the seed step.
create table if not exists municipalities (
    id          smallint primary key,
    name_mk     text not null,
    name_sq     text not null,
    name_en     text not null,
    geom        geography(MultiPolygon, 4326)
);

create table if not exists profiles (
    id                  uuid primary key references auth.users (id) on delete cascade,
    display_name        text,
    municipality_id     smallint references municipalities (id),
    lang                text check (lang in ('mk', 'sq', 'en')) default 'mk',
    trust_score         int not null default 0,
    created_at          timestamptz not null default now(),
    -- §4.6
    role                text not null check (role in ('user', 'admin')) default 'user',
    containers_added    int not null default 0,
    last_area_check_at  timestamptz
);

create table if not exists containers (
    id              uuid primary key default gen_random_uuid(),
    code            text unique not null,                       -- SK-00412
    kind            text not null check (kind in ('big', 'small')),
    category        text not null check (
                        category in ('general', 'glass', 'paper', 'plastic', 'mixed_recycling')
                    ) default 'general',
    geom            geography(Point, 4326) not null,
    municipality_id smallint references municipalities (id),
    status          text not null check (
                        status in ('ok', 'full', 'broken', 'destroyed', 'missing')
                    ) default 'ok',
    status_since    timestamptz not null default now(),
    source          text not null check (source in ('osm', 'user', 'city')) default 'osm',
    osm_id          bigint,
    added_by        uuid references auth.users (id) on delete set null,
    verified        boolean not null default false,
    created_at      timestamptz not null default now(),
    -- §4.6 soft delete. EVERY query and RPC filters on this.
    deleted_at      timestamptz
);

create table if not exists reports (
    id           uuid primary key default gen_random_uuid(),
    container_id uuid not null references containers (id) on delete cascade,
    -- Nullable so delete_my_account() can anonymise rather than destroy history (§8).
    user_id      uuid references auth.users (id) on delete set null,
    kind         text not null check (
                     kind in ('full', 'damaged', 'destroyed', 'burning', 'missing', 'dumped_around')
                 ),
    photo_path   text not null,
    note         text check (char_length(note) <= 280),
    state        text not null check (state in ('open', 'resolved', 'expired')) default 'open',
    created_at   timestamptz not null default now(),
    resolved_at  timestamptz
);

create table if not exists report_confirmations (
    id         uuid primary key default gen_random_uuid(),
    report_id  uuid not null references reports (id) on delete cascade,
    user_id    uuid references auth.users (id) on delete set null,
    kind       text not null check (kind in ('me_too', 'resolved')),
    photo_path text,
    created_at timestamptz not null default now(),
    unique (report_id, user_id, kind)
);

create table if not exists suggestions (
    id                  uuid primary key default gen_random_uuid(),
    user_id             uuid references auth.users (id) on delete set null,
    geom                geography(Point, 4326) not null,
    reason              text not null check (
                            reason in ('no_container_nearby', 'always_full', 'new_building', 'dumping_spot')
                        ),
    note                text check (char_length(note) <= 280),
    photo_path          text,
    -- Maintained by a trigger from suggestion_votes; never written by the client.
    votes               int not null default 1,
    state               text not null check (state in ('open', 'sent', 'placed', 'rejected')) default 'open',
    placed_container_id uuid references containers (id) on delete set null,
    municipality_id     smallint references municipalities (id),
    created_at          timestamptz not null default now()
);

create table if not exists suggestion_votes (
    suggestion_id uuid not null references suggestions (id) on delete cascade,
    user_id       uuid references auth.users (id) on delete cascade,
    created_at    timestamptz not null default now(),
    primary key (suggestion_id, user_id)
);

-- -----------------------------------------------------------------------------
-- §4.6 "Map your street"
-- -----------------------------------------------------------------------------

create table if not exists container_confirmations (
    container_id uuid not null references containers (id) on delete cascade,
    user_id      uuid references auth.users (id) on delete cascade,
    kind         text not null check (kind in ('exists')) default 'exists',
    created_at   timestamptz not null default now(),
    primary key (container_id, user_id)
);

create table if not exists container_requests (
    id                   uuid primary key default gen_random_uuid(),
    user_id              uuid references auth.users (id) on delete set null,
    geom                 geography(Point, 4326) not null,
    kind                 text not null check (kind in ('big', 'small')),
    category             text not null check (
                             category in ('general', 'glass', 'paper', 'plastic', 'mixed_recycling')
                         ) default 'general',
    photo_path           text not null,
    note                 text check (char_length(note) <= 280),
    state                text not null check (state in ('pending', 'approved', 'rejected')) default 'pending',
    created_container_id uuid references containers (id) on delete set null,
    created_at           timestamptz not null default now(),
    reviewed_at          timestamptz
);

create table if not exists area_checks (
    id         uuid primary key default gen_random_uuid(),
    user_id    uuid references auth.users (id) on delete set null,
    geom       geography(Point, 4326) not null,
    radius_m   int not null default 150,
    result     text not null check (result in ('all_present', 'added', 'reported_missing')),
    created_at timestamptz not null default now()
);

-- -----------------------------------------------------------------------------
-- Indexes (§6)
-- -----------------------------------------------------------------------------

create index if not exists municipalities_geom_idx      on municipalities using gist (geom);
create index if not exists containers_geom_idx          on containers using gist (geom);
create index if not exists suggestions_geom_idx         on suggestions using gist (geom);
create index if not exists container_requests_geom_idx  on container_requests using gist (geom);
create index if not exists area_checks_geom_idx         on area_checks using gist (geom);

create index if not exists reports_container_state_idx  on reports (container_id, state);
create index if not exists reports_user_idx             on reports (user_id);
create index if not exists reports_open_created_idx     on reports (created_at) where state = 'open';
create index if not exists report_confirmations_report_idx on report_confirmations (report_id);
create index if not exists container_requests_state_idx on container_requests (state);
create index if not exists area_checks_created_idx      on area_checks (created_at);
create index if not exists containers_municipality_idx  on containers (municipality_id);

-- Partial index so the map query skips soft-deleted rows cheaply (§6).
create index if not exists containers_live_idx on containers (id) where deleted_at is null;

-- -----------------------------------------------------------------------------
-- A profile row must exist for every auth user, so the RPCs can read role /
-- containers_added without a separate signup step in the app.
-- -----------------------------------------------------------------------------
create or replace function handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public, extensions
as $$
begin
    insert into public.profiles (id) values (new.id)
    on conflict (id) do nothing;
    return new;
end;
$$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function handle_new_user();
