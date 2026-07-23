-- Base job id for matrix expansion (issue #2, phase 2).
--
-- A matrix job expands into several tasks that share one job id but have distinct display names
-- ("build (linux)", "build (windows)"). job_id is the workflow's job key — used for `github.job`,
-- for `needs` resolution and for grouping a job's matrix cells. Backfilled to the display name for
-- pre-matrix rows (one cell per job).

alter table action_task
    add column job_id text not null default '';

update action_task
set job_id = name
where job_id = '';
