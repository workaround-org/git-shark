-- Follow-a-user (federated collaboration Story 1): a local user follows a remote
-- Person. We fan out to a repository follow per public repo in that Person's
-- repositories collection, tagging each with via_user_actor_id so the /following
-- page can group them under the user and unfollow-user can undo them together.
create table remote_user_follows
(
    id                   uuid primary key,
    user_id              uuid        not null references users (id) on delete cascade,
    remote_user_actor_id text        not null,
    handle               text        not null,
    created_at           timestamptz not null default now(),
    unique (user_id, remote_user_actor_id)
);

-- Null for a directly-followed repository; set to the remote Person actor id when
-- the follow was created by fanning out a followed user's repositories collection.
alter table remote_follows
    add column via_user_actor_id text;
