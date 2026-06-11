## ADDED Requirements

### Requirement: Smart HTTP clone and fetch
The system SHALL serve the Git smart HTTP protocol (upload-pack) via JGit's `GitServlet` mounted at `/git/*`, so standard Git clients can clone and fetch `https://<host>/git/<owner>/<repo>.git`.

#### Scenario: Clone public repository anonymously
- **WHEN** a Git client clones a public repository over HTTP without credentials
- **THEN** the clone succeeds and the working copy matches the repository content

#### Scenario: Clone private repository with valid token
- **WHEN** a Git client clones a private repository using HTTP Basic with username and a valid personal access token
- **THEN** the clone succeeds

#### Scenario: Clone private repository unauthenticated
- **WHEN** a Git client attempts to clone a private repository without credentials
- **THEN** the server responds `401 Unauthorized` with a `WWW-Authenticate: Basic` challenge

### Requirement: Smart HTTP push
The system SHALL serve receive-pack over smart HTTP. Push SHALL always require authentication and write permission, including on public repositories.

#### Scenario: Authorized push
- **WHEN** the repository owner pushes commits using a valid personal access token
- **THEN** the push succeeds and the refs are updated in the bare repository

#### Scenario: Anonymous push rejected
- **WHEN** an unauthenticated client attempts to push to any repository
- **THEN** the server responds `401 Unauthorized` and no refs change

#### Scenario: Push without write permission rejected
- **WHEN** an authenticated user without write permission pushes to a repository
- **THEN** the server responds `403 Forbidden` and no refs change

### Requirement: Personal access tokens for HTTP git operations
The system SHALL allow users to generate, label, and revoke personal access tokens in the UI. Tokens SHALL be shown in plaintext exactly once at creation and stored only as a cryptographic hash.

#### Scenario: Token creation
- **WHEN** a user generates a new access token
- **THEN** the plaintext token is displayed once
- **AND** only the token hash and label are persisted

#### Scenario: Revoked token rejected
- **WHEN** a Git client authenticates with a revoked token
- **THEN** the server responds `401 Unauthorized`
