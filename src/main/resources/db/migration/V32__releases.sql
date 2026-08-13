create table releases
(
    id            uuid primary key,
    repository_id uuid        not null references repositories (id) on delete cascade,
    author_id     uuid        not null references users (id) on delete cascade,
    tag_name      text        not null,
    title         text        not null,
    body          text,
    commit_id     varchar(40) not null,
    prerelease    boolean     not null default false,
    created_at    timestamptz not null default now(),
    unique (repository_id, tag_name)
);

create index releases_repository_idx on releases (repository_id);
