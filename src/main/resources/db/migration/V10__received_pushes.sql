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
