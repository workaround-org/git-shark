-- Line-level review comments on a merge request's diff. A comment is anchored to a file and a diff line by the
-- (old_line, new_line) pair (-1 where the line has no counterpart on that side, e.g. an added or deleted line).
create table merge_request_comments
(
    id               uuid primary key,
    merge_request_id uuid        not null references merge_requests (id) on delete cascade,
    author_id        uuid        not null references users (id) on delete cascade,
    file_path        text        not null,
    old_line         integer     not null default -1,
    new_line         integer     not null default -1,
    body             text        not null,
    created_at       timestamptz not null default now()
);

create index merge_request_comments_mr_idx on merge_request_comments (merge_request_id);
