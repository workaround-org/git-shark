-- CI/CD runners (issue #2, phase 1 MVP): registration + presence only. The run loop
-- (action_run/action_task/action_log) arrives in later phases.
--
-- Runners speak the Forgejo/Gitea runner.v1 Connect protocol. A runner registers once with a
-- shared registration token and receives a permanent per-runner uuid + secret; only the SHA-256
-- hash of each secret is stored (same model as access_tokens).

create table ci_runner_registration_token
(
    id            uuid primary key,
    token_hash    varchar(128) not null unique,
    created_by_id uuid references users (id) on delete set null,
    created_at    timestamptz  not null default now(),
    last_used     timestamptz
);

create table ci_runner
(
    id         uuid primary key,
    -- The uuid the runner presents in the x-runner-uuid header on every post-registration call.
    uuid       varchar(64)  not null unique,
    token_hash varchar(128) not null,
    name       text         not null,
    -- Comma-joined label set advertised via Register/Declare (empty string when none).
    labels     text         not null default '',
    version    text,
    status     varchar(32)  not null default 'IDLE'
        check (status in ('UNSPECIFIED', 'IDLE', 'ACTIVE', 'OFFLINE')),
    ephemeral  boolean      not null default false,
    last_seen  timestamptz,
    created_at timestamptz  not null default now()
);

create index idx_ci_runner_uuid on ci_runner (uuid);
