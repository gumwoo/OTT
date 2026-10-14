create table users (
    id            bigint generated always as identity primary key,
    email         varchar(320) not null unique,
    password_hash varchar(100) not null,
    role          varchar(10)  not null check (role in ('USER', 'ADMIN')),
    created_at    timestamptz  not null default now()
);

create table subscriptions (
    user_id    bigint primary key references users (id) on delete cascade,
    plan       varchar(10) not null check (plan in ('BASIC', 'PREMIUM')),
    expires_at timestamptz,
    updated_at timestamptz not null default now(),
    check ((plan = 'BASIC' and expires_at is null) or (plan = 'PREMIUM' and expires_at is not null))
);
