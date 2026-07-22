-- Per-task label requirement for runner matching (issue #2, phase 2).
--
-- A job's `runs-on` labels, comma-joined (empty = no constraint, runs on any runner). FetchTask hands
-- a task to a runner only when every one of these labels is advertised by that runner.

alter table action_task
    add column runs_on text not null default '';
