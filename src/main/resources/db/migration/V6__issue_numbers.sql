-- Per-repository, human-facing issue numbers (#1, #2, ...) so commits can reference them GitHub-style.
alter table issues
    add column number integer;

-- Backfill any pre-existing rows: number them per repository in creation order.
with numbered as (
    select id, row_number() over (partition by repository_id order by created_at, id) as n
    from issues
)
update issues i
set number = numbered.n
from numbered
where numbered.id = i.id;

alter table issues
    alter column number set not null;

alter table issues
    add constraint issues_repository_number_unique unique (repository_id, number);
