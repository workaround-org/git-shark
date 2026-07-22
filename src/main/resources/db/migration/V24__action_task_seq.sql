-- Surrogate int64 task id for the runner.v1 protocol (issue #2, phase 1).
--
-- The Forgejo/Gitea Task message identifies a task by an int64 `id` that the runner echoes back in
-- UpdateTask/UpdateLog. action_task uses a UUID primary key, so a separate stable, globally-unique
-- int64 is exposed to the wire. bigserial gives us a NOT NULL sequence-backed column; existing rows
-- (none in practice) are backfilled by the default.

alter table action_task
    add column seq bigserial;

create unique index idx_action_task_seq on action_task (seq);
