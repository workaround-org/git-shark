create table users
(
    id           uuid primary key,
    oidc_sub     varchar(255) not null unique,
    username     varchar(255) not null unique,
    display_name varchar(255),
    email        varchar(255),
    created_at   timestamptz  not null default now()
);

create table repositories
(
    id          uuid primary key,
    name        varchar(255) not null,
    owner_id    uuid         not null references users (id),
    visibility  varchar(16)  not null check (visibility in ('PUBLIC', 'PRIVATE')),
    description text,
    created_at  timestamptz  not null default now(),
    unique (owner_id, name)
);

create table ssh_keys
(
    id          uuid primary key,
    user_id     uuid         not null references users (id) on delete cascade,
    title       varchar(255) not null,
    public_key  text         not null,
    fingerprint varchar(128) not null unique,
    created_at  timestamptz  not null default now()
);

create table access_tokens
(
    id         uuid primary key,
    user_id    uuid         not null references users (id) on delete cascade,
    token_hash varchar(128) not null unique,
    label      varchar(255) not null,
    last_used  timestamptz,
    created_at timestamptz  not null default now()
);
