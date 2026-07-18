create table issue_comments
(
    id         uuid primary key,
    issue_id   uuid        not null references issues (id) on delete cascade,
    author_id  uuid        not null references users (id) on delete cascade,
    body       text        not null,
    created_at timestamptz not null default now()
);
create index issue_comments_issue_idx on issue_comments (issue_id);
