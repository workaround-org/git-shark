## ADDED Requirements

### Requirement: OIDC login
The system SHALL authenticate UI users via OIDC authorization code flow (`quarkus-oidc`). Unauthenticated access to protected pages SHALL redirect to the identity provider.

#### Scenario: Login redirect
- **WHEN** an unauthenticated user opens a protected page
- **THEN** the system redirects to the OIDC provider and, after successful login, back to the requested page

### Requirement: User provisioning on first login
The system SHALL create a local `User` record on first OIDC login, keyed by the OIDC `sub` claim, storing username, display name, and email from the token. Subsequent logins SHALL update changed profile fields.

#### Scenario: First login creates user
- **WHEN** a user authenticates via OIDC for the first time
- **THEN** a `User` record with their `sub`, username, display name, and email is persisted

#### Scenario: Repeated login updates profile
- **WHEN** an existing user logs in with a changed display name in the token
- **THEN** the stored display name is updated and no duplicate user is created

### Requirement: SSH key management
The system SHALL allow an authenticated user to add, list, and remove SSH public keys on their account. The system SHALL validate the key format on add, compute and store the fingerprint, and reject keys already registered to any account.

#### Scenario: Add valid key
- **WHEN** a user submits a valid OpenSSH public key with a title
- **THEN** the key is stored with its computed fingerprint and appears in their key list

#### Scenario: Invalid key rejected
- **WHEN** a user submits malformed key material
- **THEN** the system rejects it with a validation error

#### Scenario: Duplicate key rejected
- **WHEN** a user submits a key already registered to any account
- **THEN** the system rejects it with a conflict error

#### Scenario: Removed key stops working
- **WHEN** a user removes an SSH key
- **THEN** subsequent SSH authentication with that key fails

### Requirement: Logout
The system SHALL allow users to terminate their session via a logout action.

#### Scenario: Logout clears session
- **WHEN** a logged-in user triggers logout
- **THEN** their session is invalidated and protected pages require re-authentication
