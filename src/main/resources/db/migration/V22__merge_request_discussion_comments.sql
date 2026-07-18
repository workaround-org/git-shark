-- General (non-line) discussion comments reuse this table with a null file_path; only line-anchored
-- review comments still carry one. old_line/new_line stay -1 for general comments (their -1 default).
alter table merge_request_comments
    alter column file_path drop not null;
