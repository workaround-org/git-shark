-- Job outputs for the `needs` context (issue #2, phase 2).
--
-- A JSON object of the outputs a job reported via UpdateTask (accumulated across updates), delivered
-- to dependent jobs as `needs.<job>.outputs`.

alter table action_task
    add column outputs text not null default '{}';
