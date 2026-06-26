create table repository_pins
(
    id            uuid primary key,
    user_id       uuid        not null references users (id) on delete cascade,
    repository_id uuid        not null references repositories (id) on delete cascade,
    created_at    timestamptz not null default now(),
    unique (user_id, repository_id)
);
