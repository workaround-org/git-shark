-- Org-scoped runners (issue #2, phase 3).
--
-- In addition to the repository scope (V30), a registration token and its runners may be scoped to an
-- organisation: the runner then serves any repository owned by that organisation. NULL organisation
-- (and NULL repository) means instance scope. Scoped rows are removed with the organisation.

alter table ci_runner_registration_token
    add column organisation_id uuid references organisations (id) on delete cascade;

alter table ci_runner
    add column organisation_id uuid references organisations (id) on delete cascade;
