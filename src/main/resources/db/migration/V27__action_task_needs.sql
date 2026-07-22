-- Job dependencies for ordering (issue #2, phase 2, `needs`).
--
-- Comma-joined names of the jobs this task depends on (empty = none). A task is dispatched only once
-- every needed job in the same run has succeeded; if one fails, dependents are cancelled.

alter table action_task
    add column needs text not null default '';
