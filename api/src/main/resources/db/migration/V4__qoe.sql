create table qoe_events (
    id              bigint generated always as identity primary key,
    client_event_id uuid        not null unique,
    session_id      uuid        not null references playback_sessions (id) on delete cascade,
    kind            varchar(10) not null check (kind in ('PERIODIC', 'FINAL')),
    startup_ms      integer check (startup_ms >= 0),
    watched_ms      bigint      not null check (watched_ms >= 0),
    position_sec    integer     not null check (position_sec >= 0),
    rebuffer_ms     bigint      not null check (rebuffer_ms >= 0),
    rebuffer_count  integer     not null check (rebuffer_count >= 0),
    level_switches  integer     not null check (level_switches >= 0),
    errors          jsonb       not null default '[]',
    measured_at     timestamptz not null,
    received_at     timestamptz not null default now()
);

create index qoe_events_session_id_idx on qoe_events (session_id);
