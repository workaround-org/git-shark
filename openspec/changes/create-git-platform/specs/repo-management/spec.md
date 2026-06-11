## ADDED Requirements

### Requirement: Create repository
The system SHALL allow an authenticated user to create a repository with a name, optional description, and visibility (public or private). The system SHALL initialize a bare Git repository on the filesystem and persist its metadata in PostgreSQL. Repository names MUST be unique per owner and MUST match `[a-zA-Z0-9._-]+` (no path traversal characters).

#### Scenario: Successful creation
- **WHEN** an authenticated user submits a valid repository name and visibility
- **THEN** a bare Git repository is initialized under the storage root at a UUID-based path
- **AND** a `Repository` record with name, owner, visibility, and description is persisted
- **AND** the repository is immediately clonable by authorized users

#### Scenario: Duplicate name rejected
- **WHEN** a user creates a repository with a name they already own
- **THEN** the system rejects the request with a conflict error and no repository is created

#### Scenario: Invalid name rejected
- **WHEN** a user submits a repository name containing `/`, `..`, or other disallowed characters
- **THEN** the system rejects the request with a validation error

### Requirement: List repositories
The system SHALL list repositories visible to the current user: their own repositories plus all public repositories. Anonymous users SHALL see only public repositories.

#### Scenario: Authenticated listing
- **WHEN** an authenticated user requests the repository list
- **THEN** the response contains all repositories they own and all public repositories

#### Scenario: Anonymous listing
- **WHEN** an unauthenticated user requests the repository list
- **THEN** the response contains only public repositories

### Requirement: Delete repository
The system SHALL allow the repository owner to delete a repository. Deletion SHALL remove both the database record and the bare repository from the filesystem. Deletion MUST require explicit confirmation of the repository name.

#### Scenario: Owner deletes repository
- **WHEN** the owner confirms deletion by typing the repository name
- **THEN** the database record and the on-disk bare repository are removed
- **AND** subsequent clone or fetch attempts fail with not-found

#### Scenario: Non-owner cannot delete
- **WHEN** a user who is not the owner attempts deletion
- **THEN** the system denies the request with a forbidden error

### Requirement: Repository name resolution
The system SHALL resolve `<owner>/<repo>` names to on-disk storage paths exclusively through the database. Both HTTP and SSH transports MUST use the same resolution service.

#### Scenario: Existing repository resolves
- **WHEN** a transport resolves `alice/project.git`
- **THEN** the service returns the filesystem path of the corresponding bare repository

#### Scenario: Unknown repository
- **WHEN** a transport resolves a name with no database record
- **THEN** resolution fails with not-found and no filesystem probing occurs
