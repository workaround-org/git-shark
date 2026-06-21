-- Federation (ActivityPub / ForgeFed) support.

-- Per-actor signing keypairs. actor_type + actor_ref identify the local actor:
--   REPOSITORY -> repository id, PERSON -> user id, INSTANCE -> the literal 'instance'.
create table federation_keys
(
    id          uuid primary key,
    actor_type  varchar(16)  not null check (actor_type in ('REPOSITORY', 'PERSON', 'INSTANCE')),
    actor_ref   varchar(255) not null,
    public_pem  text         not null,
    private_pem text         not null,
    created_at  timestamptz  not null default now(),
    unique (actor_type, actor_ref)
);

-- Cache of fetched remote actors (their inbox + public key), refreshed on a TTL.
create table remote_actors
(
    id             uuid primary key,
    actor_id       text         not null unique,
    inbox          text         not null,
    public_key_pem text         not null,
    fetched_at     timestamptz  not null default now()
);

-- Followers of a local repository actor. follower_actor_id is the remote actor's id URL.
create table repository_followers
(
    id                 uuid primary key,
    repository_id      uuid         not null references repositories (id) on delete cascade,
    follower_actor_id  text         not null,
    created_at         timestamptz  not null default now(),
    unique (repository_id, follower_actor_id)
);

-- Published activities of a local actor, exposed via its outbox OrderedCollection.
create table federation_outbox
(
    id           uuid primary key,
    actor_type   varchar(16)  not null check (actor_type in ('REPOSITORY', 'PERSON', 'INSTANCE')),
    actor_ref    varchar(255) not null,
    activity_id  text         not null unique,
    payload      text         not null,
    published_at timestamptz  not null default now()
);

create index idx_outbox_actor on federation_outbox (actor_type, actor_ref, published_at);

-- Idempotency log of accepted inbound activities (dedup by activity id).
create table federation_inbox
(
    id          uuid primary key,
    activity_id text         not null unique,
    received_at timestamptz  not null default now()
);

-- Outbound delivery queue. One row per (activity, target inbox). Drained by a scheduled worker.
create table federation_delivery
(
    id              uuid primary key,
    target_inbox    text         not null,
    actor_key_ref   varchar(255) not null,
    actor_key_type  varchar(16)  not null,
    signer_key_id   text         not null,
    payload         text         not null,
    attempts        int          not null default 0,
    next_attempt_at timestamptz  not null default now(),
    state           varchar(16)  not null default 'PENDING' check (state in ('PENDING', 'DELIVERED', 'FAILED')),
    last_error      text,
    created_at      timestamptz  not null default now()
);

create index idx_delivery_due on federation_delivery (state, next_attempt_at);
