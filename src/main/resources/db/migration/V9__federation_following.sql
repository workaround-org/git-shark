-- Outbound federation following (issue #3).

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

-- Follower-side feed: Push activities received from followed remote repositories.
-- remote_actor_id is the sending repository's actor id; rows are only stored while
-- at least one local user follows that actor.
create table received_pushes
(
    id              uuid primary key,
    remote_actor_id text        not null,
    activity_id     text        not null unique,
    summary         text,
    target          text,
    payload         text        not null,
    received_at     timestamptz not null default now()
);

create index idx_received_pushes_actor on received_pushes (remote_actor_id, received_at);
