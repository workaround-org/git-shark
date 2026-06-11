## ADDED Requirements

### Requirement: Repository overview page
The system SHALL render a server-side (Qute) repository list page showing the repositories visible to the current user, with name, owner, visibility, and description.

#### Scenario: List rendered for authenticated user
- **WHEN** an authenticated user opens the repository list page
- **THEN** the page shows their repositories and all public repositories

### Requirement: File and tree browser
The system SHALL render the file tree of a repository at a selected branch, tag, or commit, reading directly from the bare repository via JGit. Text file contents SHALL be rendered HTML-escaped; binary files SHALL be offered as downloads.

#### Scenario: Browse directory
- **WHEN** a user opens a directory path at a given ref
- **THEN** the page lists the directory's files and subdirectories at that ref

#### Scenario: View text file
- **WHEN** a user opens a text file
- **THEN** its content is displayed HTML-escaped

#### Scenario: Binary file
- **WHEN** a user opens a binary file
- **THEN** the page offers a download link instead of inline rendering

#### Scenario: Empty repository
- **WHEN** a user opens a repository with no commits
- **THEN** the page shows setup instructions including clone URLs instead of a file tree

### Requirement: Commit history view
The system SHALL render a paginated commit log for a selected ref, showing commit ID (abbreviated), message, author, and date.

#### Scenario: Paginated log
- **WHEN** a user opens the commit history of a branch with more commits than the page size
- **THEN** the page shows one page of commits with navigation to older commits

### Requirement: Branch and tag listing
The system SHALL render the repository's branches and tags with links into the file browser at that ref.

#### Scenario: Branches listed
- **WHEN** a user opens the branches page
- **THEN** all branches are listed with the default branch marked

### Requirement: Clone URL display
The system SHALL display both HTTP and SSH clone URLs on the repository page.

#### Scenario: Clone URLs shown
- **WHEN** a user views a repository page
- **THEN** the HTTP URL (`https://<host>/git/<owner>/<repo>.git`) and SSH URL (`ssh://git@<host>:<port>/<owner>/<repo>.git`) are displayed

### Requirement: UI access control
The system SHALL deny access to private repository pages for users without read permission and SHALL hide mutation controls (delete, settings) from non-owners.

#### Scenario: Private repository hidden
- **WHEN** a user without read permission opens a private repository URL
- **THEN** the system responds with not-found or forbidden, revealing no repository details
