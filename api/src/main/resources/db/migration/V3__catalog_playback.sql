create table contents (
    id                     varchar(64)  primary key check (id ~ '^[a-z0-9-]+$'),
    title                  varchar(200) not null,
    description            text         not null default '',
    required_plan          varchar(10)  not null check (required_plan in ('BASIC', 'PREMIUM')),
    duration_sec           integer      not null check (duration_sec > 0),
    current_version        integer,
    max_published_version  integer,
    publish_epoch          integer      not null default 0,
    last_allocated_version integer      not null default 0,
    created_at             timestamptz  not null default now()
);

create table playback_sessions (
    id                uuid primary key,
    user_id           bigint      not null references users (id) on delete cascade,
    content_id        varchar(64) not null references contents (id),
    version           integer     not null,
    country           varchar(2),
    issued_at         timestamptz not null,
    last_refreshed_at timestamptz,
    expires_at        timestamptz not null
);

create index playback_sessions_user_id_idx on playback_sessions (user_id);
