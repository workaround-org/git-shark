-- CI/CD run loop (issue #2, phase 1): the run-persistence trio deferred by V19.
--
-- action_run   -- one workflow run triggered by an event against a repository
-- action_task  -- one job within a run; claimed and executed by a ci_runner (FetchTask)
-- action_log   -- one log row of a task; appended by UpdateLog, replayed from action_task.log_length
--
-- Phase 1 materializes a single task per run (on: push, single job). needs/matrix fan-out is phase 2.

create table action_run
(
    id             uuid primary key,
    repository_id  uuid        not null references repositories (id) on delete cascade,
    -- Per-repository sequential run number (#1, #2, ...); stable UI URLs, mirrors issues.number.
    number         integer     not null,
    workflow_name  text        not null,
    workflow_file  text        not null,
    event          varchar(64) not null,
    ref            text        not null,
    commit_sha     varchar(64) not null,
    triggered_by_id uuid       references users (id) on delete set null,
    status         varchar(32) not null default 'PENDING'
        check (status in ('PENDING', 'RUNNING', 'SUCCESS', 'FAILURE', 'CANCELLED')),
    created_at     timestamptz not null default now(),
    started_at     timestamptz,
    finished_at    timestamptz,
    unique (repository_id, number)
);

create index idx_action_run_repository on action_run (repository_id);

create table action_task
(
    id          uuid primary key,
    run_id      uuid        not null references action_run (id) on delete cascade,
    name        text        not null,
    payload     text,
    -- The runner that claimed the task via FetchTask; null while pending. Cleared if the runner is deleted.
    runner_id   uuid        references ci_runner (id) on delete set null,
    status      varchar(32) not null default 'PENDING'
        check (status in ('PENDING', 'RUNNING', 'SUCCESS', 'FAILURE', 'CANCELLED')),
    -- Count of durably-persisted log rows; the resume/ack offset for UpdateLog.
    log_length  integer     not null default 0,
    -- Deadline for a claimed task; still running past it is reclaimed as a zombie. Null while pending.
    deadline    timestamptz,
    created_at  timestamptz not null default now(),
    started_at  timestamptz,
    finished_at timestamptz
);

create index idx_action_task_run on action_task (run_id);
create index idx_action_task_status on action_task (status);

create table action_log
(
    id         uuid primary key,
    task_id    uuid        not null references action_task (id) on delete cascade,
    -- 0-based position of this row within the task's log stream.
    line_index integer     not null,
    content    text        not null,
    timestamp  timestamptz not null default now(),
    unique (task_id, line_index)
);

create index idx_action_log_task on action_log (task_id, line_index);
