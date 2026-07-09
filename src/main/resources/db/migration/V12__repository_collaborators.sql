create table repository_collaborators
(
    id            uuid primary key,
    repository_id uuid        not null references repositories (id) on delete cascade,
    user_id       uuid        not null references users (id) on delete cascade,
    created_at    timestamptz not null default now(),
    unique (repository_id, user_id)
);
