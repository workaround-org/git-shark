## ADDED Requirements

### Requirement: SSH Git server
The system SHALL run an embedded SSH server (Apache MINA SSHD) on a configurable port (default 2222) inside the same service process, supporting `git-upload-pack` and `git-receive-pack` for URLs of the form `ssh://git@<host>:<port>/<owner>/<repo>.git`.

#### Scenario: Clone over SSH
- **WHEN** a user with a registered SSH key clones a repository they can read over SSH
- **THEN** the clone succeeds

#### Scenario: Push over SSH
- **WHEN** the repository owner pushes over SSH with a registered key
- **THEN** the push succeeds and refs are updated

#### Scenario: Non-git command rejected
- **WHEN** an SSH client requests a shell or any command other than `git-upload-pack`/`git-receive-pack`
- **THEN** the server rejects the request without executing anything

### Requirement: Public-key authentication
The system SHALL authenticate SSH connections exclusively by public key, resolving the presented key against registered user keys via fingerprint lookup. Password authentication MUST be disabled.

#### Scenario: Registered key accepted
- **WHEN** a client authenticates with a key registered to a user account
- **THEN** the session is authenticated as that user

#### Scenario: Unknown key rejected
- **WHEN** a client authenticates with a key not registered to any user
- **THEN** authentication fails

### Requirement: SSH authorization matches HTTP authorization
The system SHALL enforce the same per-repository access policy on SSH as on HTTP: read requires read permission, receive-pack requires write permission.

#### Scenario: Read of private repository denied
- **WHEN** an authenticated SSH user without read permission runs upload-pack on a private repository
- **THEN** the operation is denied

#### Scenario: Write without permission denied
- **WHEN** an authenticated SSH user without write permission runs receive-pack
- **THEN** the operation is denied and no refs change

### Requirement: Stable host key
The system SHALL generate an SSH host key on first startup and persist it on the data volume so the host identity remains stable across restarts.

#### Scenario: Restart keeps host identity
- **WHEN** the service restarts
- **THEN** SSH clients with the previously recorded host key connect without a host-key-changed warning
