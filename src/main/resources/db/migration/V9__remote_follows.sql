-- Outbound follows: a local user following a repository on a remote instance.
-- follow_activity_id is the id of the Follow activity we sent; the inbound Accept
-- references it to confirm the follow (state PENDING -> ACCEPTED).
create table remote_follows
(
    id                 uuid primary key,
    user_id            uuid         not null references users (id) on delete cascade,
    remote_actor_id    text         not null,
    follow_activity_id text         not null unique,
    state              varchar(16)  not null default 'PENDING' check (state in ('PENDING', 'ACCEPTED')),
    created_at         timestamptz  not null default now(),
    unique (user_id, remote_actor_id)
);
