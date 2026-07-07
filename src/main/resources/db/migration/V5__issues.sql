create table issues
(
    id            uuid primary key,
    repository_id uuid        not null references repositories (id) on delete cascade,
    author_id     uuid        not null references users (id) on delete cascade,
    title         text        not null,
    description   text,
    status        varchar(32) not null default 'PLANNED'
        check (status in ('PLANNED', 'IN_DEVELOPMENT', 'DONE')),
    created_at    timestamptz not null default now()
);

create index issues_repository_idx on issues (repository_id);
