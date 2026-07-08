-- Per-repository merge requests: a request to merge one branch into another, reviewed and merged in the UI.
create table merge_requests
(
    id            uuid primary key,
    repository_id uuid        not null references repositories (id) on delete cascade,
    author_id     uuid        not null references users (id) on delete cascade,
    number        integer     not null,
    title         text        not null,
    description   text,
    source_branch text        not null,
    target_branch text        not null,
    status        varchar(32) not null default 'OPEN'
        check (status in ('OPEN', 'MERGED', 'CLOSED')),
    created_at    timestamptz not null default now(),
    merged_at     timestamptz,
    constraint merge_requests_repository_number_unique unique (repository_id, number)
);

create index merge_requests_repository_idx on merge_requests (repository_id);
