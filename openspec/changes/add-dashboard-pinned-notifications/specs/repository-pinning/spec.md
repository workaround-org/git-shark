## ADDED Requirements

### Requirement: User can pin a repository

The system SHALL allow an authenticated user to pin any repository that is visible to them. A pin is
a per-user marker; pinning a repository affects only the pinning user's view and does not change the
repository for anyone else.

#### Scenario: Pin a visible repository

- **WHEN** an authenticated user pins a repository visible to them
- **THEN** the repository is recorded as pinned for that user
- **AND** it appears in that user's pinned-repositories section on the next dashboard view

#### Scenario: Pinning is idempotent

- **WHEN** a user pins a repository they have already pinned
- **THEN** the system records no duplicate pin and reports success
- **AND** the repository appears exactly once in the pinned section

#### Scenario: Cannot pin a repository that is not visible

- **WHEN** an authenticated user attempts to pin a repository they cannot see
- **THEN** the system rejects the request and creates no pin

#### Scenario: Anonymous users cannot pin

- **WHEN** an unauthenticated request attempts to pin a repository
- **THEN** the system rejects the request as unauthorized and creates no pin

### Requirement: User can unpin a repository

The system SHALL allow an authenticated user to remove a pin they previously created.

#### Scenario: Unpin a pinned repository

- **WHEN** a user unpins a repository they had pinned
- **THEN** the pin is removed
- **AND** the repository no longer appears in that user's pinned section

#### Scenario: Unpinning a repository that is not pinned

- **WHEN** a user unpins a repository they had not pinned
- **THEN** the system reports success and makes no change

### Requirement: Pinned repositories are scoped to the owning user

The system SHALL store pins per user so that one user's pins are never visible to another user.

#### Scenario: Pins are isolated between users

- **WHEN** user A pins a repository and user B views their own dashboard
- **THEN** the repository does not appear in user B's pinned section unless user B also pinned it

#### Scenario: Pins persist across sessions

- **WHEN** a user pins a repository, logs out, and logs back in
- **THEN** the repository is still listed in their pinned section

### Requirement: Pinned repositories are listable for the current user

The system SHALL provide the set of repositories pinned by the current user, ordered deterministically
(e.g. by repository name), for rendering the dashboard pinned section.

#### Scenario: List pinned repositories

- **WHEN** the dashboard is rendered for an authenticated user
- **THEN** the pinned section lists exactly the repositories that user has pinned, in a stable order

#### Scenario: Empty pinned section

- **WHEN** an authenticated user with no pins views the dashboard
- **THEN** the pinned section renders an explicit empty state rather than an empty table

### Requirement: Pin state is reflected in the full repository list

The system SHALL indicate, for each repository in the full "all repositories" list, whether the current
user has pinned it, and SHALL offer the inverse action (pin if unpinned, unpin if pinned).

#### Scenario: Toggle control reflects current state

- **WHEN** a user views the all-repositories list
- **THEN** each row offers a "Pin" action for unpinned repos and an "Unpin" action for pinned repos

### Requirement: Removing a repository removes its pins

The system SHALL ensure that when a repository is deleted, any pins referencing it are also removed so
no dangling pins remain.

#### Scenario: Pins cleaned up on repository deletion

- **WHEN** a repository that one or more users have pinned is deleted
- **THEN** all pins referencing that repository are removed
