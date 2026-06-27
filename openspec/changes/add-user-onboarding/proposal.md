## Why

The local `username` is copied verbatim from the OIDC `preferred_username` claim. With kanidm
that claim is the SPN form `miggi@sso.mymiggi.de`, and because `username` is a path segment in
every URL (repo paths `/repos/{owner}/{name}`, git HTTP/SSH clone URLs, ActivityPub actor IDs
`/ap/users/{username}`, webfinger `acct:{username}@host`), the `@` and `.` produce broken, brittle,
and confusing URLs. The handle that appears in URLs must be user-owned and URL-safe, not a raw IdP claim.

## What Changes

- On first OIDC login the local user record is created **without** a username; the IdP claim is no
  longer persisted as the handle.
- A one-time **onboarding page** (`/onboarding`) lets the user choose a URL-safe handle, pre-filled
  with a sanitized suggestion derived from the `preferred_username` claim (e.g. `miggi@sso.mymiggi.de`
  → `miggi`).
- A guard redirects every authenticated app page to `/onboarding` until a handle is chosen.
- The handle is validated (`^[a-z0-9][a-z0-9-]{0,38}$`), unique, and used in all repo/SSH/federation URLs.
- The OIDC `name` claim is stored as a free-form **display name**, editable later.
- Existing users can rename their handle and edit their display name via a new `/settings/profile` page.
- **BREAKING** (data): `users.username` becomes nullable; a `null` handle marks an onboarding-pending account.

## Capabilities

### New Capabilities
- `user-onboarding`: First-login handle selection — provisioning without a claim-derived handle,
  the onboarding redirect guard, handle suggestion, handle validation/uniqueness, display-name
  capture, and post-onboarding renaming via settings.

### Modified Capabilities
<!-- No existing capability spec covers user identity; provisioning lives only in create-git-platform design (D4). -->

## Impact

- Code: `account/CurrentUser`, `account/UserProvisioningService`, `model/User`,
  new `account/OnboardingResource`, `account/OnboardingFilter`, `account/HandleSuggester`,
  `account/UsernameService` (+ exceptions), `account/SettingsResource`.
- Schema: new Flyway migration `V4__username_nullable.sql` (drop NOT NULL on `users.username`,
  keep unique).
- Config: add `/onboarding` to `quarkus.http.auth.permission.authenticated.paths`.
- Templates: new `HomeResource/onboarding.html`, `SettingsResource/profile.html`.
- Dev: seeded `alice` (handle already set) and the dev `adopt-username` path are unaffected.
