-- Fork relationship: a repository may point at the repository it was forked from. Nullable — most
-- repositories are not forks. ON DELETE SET NULL so deleting a source repository leaves its forks
-- standing as independent repositories rather than cascading their removal.
alter table repositories
    add column parent_repo_id uuid references repositories (id) on delete set null;

create index idx_repositories_parent on repositories (parent_repo_id);
