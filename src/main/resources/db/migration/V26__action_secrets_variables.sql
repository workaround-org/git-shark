-- Repository-level CI secrets and variables (issue #2, phase 2).
--
-- Secrets are stored encrypted (same SecretCrypto envelope as push-mirror credentials) and decrypted
-- only when delivered to a runner in FetchTask. Variables are plain configuration. Both are unique by
-- name within a repository and removed with it.

create table action_secret
(
    id              uuid primary key,
    repository_id   uuid        not null references repositories (id) on delete cascade,
    name            varchar(255) not null,
    value_encrypted text        not null,
    created_at      timestamptz not null default now(),
    unique (repository_id, name)
);

create index idx_action_secret_repository on action_secret (repository_id);

create table action_variable
(
    id            uuid primary key,
    repository_id uuid         not null references repositories (id) on delete cascade,
    name          varchar(255) not null,
    value         text         not null,
    created_at    timestamptz  not null default now(),
    unique (repository_id, name)
);

create index idx_action_variable_repository on action_variable (repository_id);
