-- Optional person responsible for an issue. Nullable (unassigned by default) and, unlike author_id,
-- set to null rather than cascade-deleted when the referenced user goes away, so the issue survives.
ALTER TABLE issues
    ADD COLUMN assignee_id uuid references users (id) on delete set null;

create index issues_assignee_idx on issues (assignee_id);
