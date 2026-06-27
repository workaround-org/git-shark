## Context

git-shark is a server-rendered Quarkus + Qute app. User records are provisioned from OIDC claims in
`CurrentUser.provision()`; `username` currently comes from `preferred_username` and is used directly
as a URL path segment everywhere (`/repos/{owner}/{name}`, git HTTP/SSH, `/ap/users/{username}`,
webfinger `acct:`). kanidm returns the SPN form `user@domain` for that claim, which is not URL-safe.
The OIDC subject (`User.oidcSub`) is already the stable identity key. Auth is declarative
(`quarkus.http.auth.permission.authenticated.paths`). Flyway owns the schema; Hibernate validates.

## Goals / Non-Goals

**Goals:**
- Make the handle user-owned, URL-safe, unique, and decoupled from the IdP claim.
- Force a one-time handle choice on first login before the app is usable.
- Keep the IdP `name` claim as an editable display name.
- Provide a settings-based rename path for existing/SPN-style users.

**Non-Goals:**
- Migrating or auto-rewriting existing SPN-style handles (handled via the rename page, not a batch job).
- Changing git transport / SSH / federation auth.
- Any client-side SPA work (the app is server-rendered).

## Decisions

- **Null `username` is the onboarding-pending signal.** No extra boolean column; a `null`/blank handle
  means "not onboarded". Rationale: minimal schema change, and Postgres allows multiple NULLs under a
  unique constraint so many un-onboarded accounts coexist. Alternative (a `onboardingCompleted` flag)
  adds a column with no extra information.
- **Stop deriving the handle from the claim in the JWT provisioning path only.** A new
  `UserProvisioningService.provisionFromOidc(sub, claimUsername, displayName, email)` creates new users
  with `username = null` (or, in dev with `adopt-username`, adopts a seeded row by `claimUsername`).
  The existing `provision(sub, username, …)` is left intact so the non-JWT path (`@TestSecurity`,
  basic/token auth) and `UserProvisioningServiceTest` keep setting a username. Rationale: avoids
  breaking the entire existing UI test suite, which relies on auto-provisioned usernames, while the
  real OIDC login path forces onboarding.
- **Guard via a JAX-RS `ContainerRequestFilter`.** It redirects to `/onboarding` when the OIDC identity
  is non-anonymous, the current user's handle is blank, and the path is not `onboarding`/`logout`.
  Rationale: git/SSH/federation use their own (non-OIDC) auth, so they appear anonymous/non-session to
  the filter and are skipped automatically — no path allowlisting of transport routes needed.
- **Handle logic in small testable units.** `HandleSuggester.suggest(claim)` is a pure function;
  `UsernameService.choose(user, handle)` validates (`^[a-z0-9][a-z0-9-]{0,38}$`) + uniqueness and throws
  `InvalidUsernameException` / `UsernameTakenException`, mirroring the existing `InvalidSshKeyException`
  pattern. Both onboarding and settings-rename reuse `choose`.

## Risks / Trade-offs

- [Blank-handle account reaches a URL-building code path before onboarding] → the guard runs before any
  resource; templates for handle-less users are never rendered because every page redirects first.
- [Dev adoption depends on the claim] → `provisionFromOidc` keeps the dev adopt branch keyed on
  `claimUsername`, so seeded `alice` still logs in without onboarding.
- [Unique constraint + NULLs] → relies on Postgres semantics (multiple NULLs allowed); the migration only
  drops NOT NULL and keeps the existing unique index.

## Migration Plan

- Flyway `V4__username_nullable.sql`: `alter table users alter column username drop not null;`
- Forward-only; rollback would require re-adding NOT NULL after backfilling handles. No data backfill —
  existing rows already have handles.
