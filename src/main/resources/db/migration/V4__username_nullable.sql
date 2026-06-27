-- A null username marks an onboarding-pending account: on first OIDC login the user record is
-- created without a handle and the user chooses a URL-safe one on the onboarding page. The unique
-- constraint stays (Postgres allows multiple NULLs, so many onboarding-pending accounts coexist).
alter table users
    alter column username drop not null;
