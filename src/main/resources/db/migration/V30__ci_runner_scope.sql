-- Repo-scoped runners (issue #2, phase 3).
--
-- A registration token and the runners it creates may be scoped to a single repository; a NULL
-- repository means instance scope (any repository), the existing behaviour. A scoped runner only
-- receives tasks from its repository. Scoped rows are removed with the repository.

alter table ci_runner_registration_token
    add column repository_id uuid references repositories (id) on delete cascade;

alter table ci_runner
    add column repository_id uuid references repositories (id) on delete cascade;
