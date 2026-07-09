-- Push mirrors: replicate a repository to an external remote on every push.

-- One row per configured mirror. encrypted_secret holds the HTTPS password/token or the SSH
-- private key PEM, encrypted at rest by the application (GITSHARK_SECRET_KEY). public_key and
-- host_key are SSH-only: the generated deploy key shown to the owner, and the remote host key
-- pinned on first successful contact.
create table push_mirror
(
    id               uuid primary key,
    repository_id    uuid        not null references repositories (id) on delete cascade,
    remote_url       text        not null,
    auth_type        varchar(8)  not null check (auth_type in ('HTTPS', 'SSH')),
    username         varchar(255),
    encrypted_secret text        not null,
    public_key       text,
    host_key         text,
    enabled          boolean     not null default true,
    last_attempt_at  timestamptz,
    last_success_at  timestamptz,
    last_error       text,
    created_at       timestamptz not null default now()
);

create index idx_push_mirror_repo on push_mirror (repository_id);

-- Async sync queue, one PENDING row per mirror at most (rapid pushes coalesce). Drained by a
-- scheduled worker with exponential backoff; exhausted rows become FAILED (dead-lettered) and the
-- next repository push enqueues a fresh one.
create table mirror_sync
(
    id              uuid        not null primary key,
    mirror_id       uuid        not null references push_mirror (id) on delete cascade,
    state           varchar(16) not null default 'PENDING' check (state in ('PENDING', 'SYNCED', 'FAILED')),
    attempts        int         not null default 0,
    next_attempt_at timestamptz not null default now(),
    last_error      text,
    created_at      timestamptz not null default now()
);

create index idx_mirror_sync_due on mirror_sync (state, next_attempt_at);
