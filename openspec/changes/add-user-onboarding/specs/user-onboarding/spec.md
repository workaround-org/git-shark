## ADDED Requirements

### Requirement: First-login provisioning without a claim-derived handle

On first OIDC login the system SHALL create the local user record keyed by the OIDC subject (`sub`)
and SHALL NOT persist the `preferred_username` claim as the handle. The `name` claim SHALL be stored
as the display name and the `email` claim as the email. A newly provisioned user SHALL have no handle
(onboarding pending) until one is chosen.

#### Scenario: New OIDC subject is provisioned without a handle

- **WHEN** a user logs in with an OIDC subject that has no local record
- **THEN** a user record is created keyed by that subject with no handle set
- **AND** the display name is taken from the `name` claim and email from the `email` claim

#### Scenario: Returning user keeps the chosen handle

- **WHEN** a user who has already chosen a handle logs in again
- **THEN** their existing handle is preserved and display name/email are refreshed from the claims

#### Scenario: Dev adopt-username path is preserved

- **WHEN** dev `adopt-username` is enabled and an unknown subject logs in with a `preferred_username`
  matching an existing seeded handle
- **THEN** the existing record is re-keyed to the new subject and its handle is kept (no onboarding)

### Requirement: Onboarding redirect guard

The system SHALL redirect every authenticated application page to `/onboarding` while the current
user has no handle, and SHALL NOT redirect once a handle is set. The guard SHALL NOT affect anonymous
requests, the onboarding page itself, or logout. Git transport, SSH, and federation requests (which do
not use the interactive OIDC session) SHALL be unaffected.

#### Scenario: Handle-less user is redirected to onboarding

- **WHEN** an authenticated user with no handle requests any application page (e.g. `/` or `/settings/keys`)
- **THEN** the response is a redirect to `/onboarding`

#### Scenario: Onboarded user reaches the app

- **WHEN** an authenticated user with a handle requests an application page
- **THEN** the request is served normally without redirect

#### Scenario: Onboarding page itself is reachable

- **WHEN** a handle-less user requests `/onboarding`
- **THEN** the onboarding page is served (no redirect loop)

### Requirement: Suggested handle

The onboarding page SHALL pre-fill the handle field with a suggestion derived from the
`preferred_username` claim by stripping any `@domain` part, lowercasing, replacing characters outside
the allowed set with `-`, trimming leading/trailing separators, and clamping to the allowed length.

#### Scenario: SPN claim is sanitized to a suggestion

- **WHEN** the `preferred_username` claim is `miggi@sso.mymiggi.de`
- **THEN** the suggested handle is `miggi`

#### Scenario: Invalid characters are sanitized

- **WHEN** the claim contains uppercase letters or characters outside `[a-z0-9-]`
- **THEN** the suggestion is lowercased and the invalid characters are replaced or trimmed to fit the pattern

### Requirement: Handle validation and uniqueness

The system SHALL accept a handle only if it matches `^[a-z0-9][a-z0-9-]{0,38}$` and is not already used
by another user. Invalid or already-taken handles SHALL be rejected with a clear error and SHALL NOT be
persisted.

#### Scenario: Valid unique handle is accepted

- **WHEN** a handle-less user submits a handle matching the pattern that no other user holds
- **THEN** the handle is stored on their record and they are redirected to the app

#### Scenario: Invalid handle is rejected

- **WHEN** a user submits a handle that does not match the pattern (bad characters, too long, or leading `-`)
- **THEN** the submission is rejected with a validation error and no handle is stored

#### Scenario: Taken handle is rejected

- **WHEN** a user submits a handle already held by another user
- **THEN** the submission is rejected with a uniqueness error and no handle is stored

### Requirement: URLs use the chosen handle

All repository, SSH, and federation URLs SHALL use the user's chosen handle and SHALL never expose the
raw `preferred_username` SPN claim.

#### Scenario: Repo and actor URLs use the handle

- **WHEN** a user with handle `miggi` owns a repository
- **THEN** its web path, clone URLs, ActivityPub actor ID, and webfinger `acct:` all use `miggi`, not the SPN claim

### Requirement: Rename handle and edit display name in settings

After onboarding, the system SHALL let a user change their handle (subject to the same validation and
uniqueness rules) and edit their display name from a settings page. This provides the rename path for
existing users with SPN-style handles.

#### Scenario: User renames their handle

- **WHEN** a user submits a new valid, unused handle on the settings profile page
- **THEN** their handle is updated and subsequent URLs use the new handle

#### Scenario: Rename collision is rejected

- **WHEN** a user submits a handle already held by another user
- **THEN** the rename is rejected with a uniqueness error and the handle is unchanged

#### Scenario: Display name is editable

- **WHEN** a user submits a new display name
- **THEN** the display name is updated while the handle and OIDC subject are unchanged
