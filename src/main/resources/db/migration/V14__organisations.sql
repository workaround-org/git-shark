create table organisations
(
    id           uuid primary key,
    name         varchar(255) not null unique,
    display_name varchar(255),
    created_at   timestamptz  not null default now()
);

create table organisation_members
(
    id              uuid primary key,
    organisation_id uuid        not null references organisations (id) on delete cascade,
    user_id         uuid        not null references users (id) on delete cascade,
    role            varchar(16) not null check (role in ('GUEST', 'MEMBER', 'OWNER')),
    created_at      timestamptz not null default now(),
    unique (organisation_id, user_id)
);

-- A repository is owned by exactly one user or one organisation. The org/user handle namespace is
-- shared; cross-table uniqueness is enforced by the application inside the creating transaction.
alter table repositories
    rename column owner_id to owner_user_id;
alter table repositories
    alter column owner_user_id drop not null;
alter table repositories
    add column owner_org_id uuid references organisations (id);
alter table repositories
    add constraint repositories_exactly_one_owner check (num_nonnulls(owner_user_id, owner_org_id) = 1);
alter table repositories
    add constraint repositories_owner_org_name_unique unique (owner_org_id, name);
