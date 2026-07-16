-- Optional assignee and reviewer for a merge request. Both nullable (unassigned by default) and, unlike
-- author_id, set to null rather than cascade-deleted when the referenced user goes away, so the MR survives.
ALTER TABLE merge_requests
    ADD COLUMN assignee_id uuid references users (id) on delete set null,
    ADD COLUMN reviewer_id uuid references users (id) on delete set null;

create index merge_requests_assignee_idx on merge_requests (assignee_id);
create index merge_requests_reviewer_idx on merge_requests (reviewer_id);
