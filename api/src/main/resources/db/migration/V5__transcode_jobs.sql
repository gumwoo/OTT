create table content_versions (
    content_id      varchar(64) not null references contents (id),
    version         integer     not null check (version > 0),
    profile_version varchar(32) not null,
    source_hash     varchar(64) not null check (source_hash ~ '^[0-9a-f]{64}$'),
    created_at      timestamptz not null default now(),
    primary key (content_id, version)
);

create table transcode_jobs (
    id                uuid primary key,
    content_id        varchar(64)   not null,
    version           integer       not null,
    publish_epoch     integer       not null,
    source_key        varchar(512)  not null,
    source_hash       varchar(64)   not null,
    profile_version   varchar(32)   not null,
    status            varchar(24)   not null check (status in ('REQUESTED', 'RUNNING', 'VALIDATED', 'UPLOADED',
                                                               'PUBLISHED', 'PUBLISHED_SUPERSEDED', 'FAILED')),
    failure_code      varchar(32),
    superseded_reason varchar(16) check (superseded_reason in ('STALE_EPOCH', 'OLDER_VERSION')),
    error             varchar(2000),
    k8s_job_name      varchar(63)   not null unique,
    started_at        timestamptz,
    heartbeat_at      timestamptz,
    created_at        timestamptz   not null default now(),
    updated_at        timestamptz   not null default now(),
    unique (content_id, version),
    foreign key (content_id, version) references content_versions (content_id, version),
    check ((status = 'FAILED') = (failure_code is not null)),
    check ((status = 'PUBLISHED_SUPERSEDED') = (superseded_reason is not null))
);

create index transcode_jobs_status_idx on transcode_jobs (status);

-- 이 마이그레이션 전에 공개된 콘텐츠의 버전 번호를 다시 할당하지 않게 한다
update contents
set last_allocated_version = greatest(last_allocated_version, coalesce(current_version, 0)),
    max_published_version  = coalesce(max_published_version, current_version);
