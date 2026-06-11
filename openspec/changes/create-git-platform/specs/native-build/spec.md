## ADDED Requirements

### Requirement: Native image build
The system SHALL compile to a GraalVM native image via the standard Quarkus native build (`mvn package -Dnative`). All required reflection, resource, and JNI configuration for JGit, MINA SSHD, and their crypto dependencies SHALL be included in the build.

#### Scenario: Native build succeeds
- **WHEN** the native build is executed
- **THEN** it produces a runnable native binary without build-time errors

### Requirement: Native runtime parity
The native binary SHALL provide the same functionality as JVM mode: HTTP server, SSH server, OIDC login, database access via Flyway-migrated schema, and Git operations over both transports.

#### Scenario: Native smoke test — HTTP clone and push
- **WHEN** the native binary runs against Postgres and a Git client clones and pushes over smart HTTP
- **THEN** both operations succeed identically to JVM mode

#### Scenario: Native smoke test — SSH clone
- **WHEN** a Git client clones over SSH from the running native binary
- **THEN** the clone succeeds

#### Scenario: Native startup
- **WHEN** the native binary starts with a reachable database
- **THEN** Flyway migrations run and HTTP and SSH ports are listening

### Requirement: CI native verification
The CI pipeline SHALL build the native image and run the native smoke tests (`@QuarkusIntegrationTest`) on every main-branch build, so native incompatibilities are caught at merge time, not release time.

#### Scenario: Regression caught in CI
- **WHEN** a change introduces a dependency or code path that breaks the native build or native smoke tests
- **THEN** the CI build fails
