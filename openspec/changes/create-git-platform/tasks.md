## 1. Project Foundation

- [x] 1.1 Add dependencies to the bootstrapped Quarkus project: `quarkus-qute`, `quarkus-undertow`, `quarkus-rest`, `quarkus-oidc`, `quarkus-jdbc-postgresql`, Panache Next, `quarkus-flyway`, `org.eclipse.jgit`, `org.eclipse.jgit.http.server`, `sshd-core`, `sshd-git`
- [x] 1.2 Configure `application.properties`: datasource, Flyway, OIDC (Keycloak Dev Services for dev/test), storage root path, SSH port (default 2222)
- [x] 1.3 Write Flyway `V1__init.sql`: `users`, `repositories`, `ssh_keys`, `access_tokens` tables (UUID PKs, unique constraints per design D5)
- [x] 1.4 Create Panache Next entities `User`, `Repository`, `SshKey`, `AccessToken` with `WithId.AutoUUID`; failing persistence test first, then green

## 2. Native Build Baseline (fail fast per design risk)

- [x] 2.1 Add native-image config skeleton under `src/main/resources/META-INF/native-image/` for JGit and MINA SSHD
- [x] 2.2 Verify `mvn package -Dnative` builds with all dependencies on the classpath (empty service, no features yet)
- [x] 2.3 Add `@QuarkusIntegrationTest` smoke test scaffold that boots the binary and checks HTTP + SSH ports listening

## 3. Repository Management

- [x] 3.1 Test-first: `GitRepositoryService` — create bare repo at UUID path, persist metadata, reject duplicate/invalid names
- [x] 3.2 Test-first: name resolution `<owner>/<repo>` → filesystem path via DB only; not-found for unknown names
- [x] 3.3 Test-first: repository deletion — removes DB record and on-disk repo, owner-only
- [x] 3.4 Test-first: visibility-filtered repository listing (own + public; anonymous sees public only)
- [x] 3.5 Implement `AccessPolicy` service: owner read/write, public world-readable, private owner-only (shared by all transports)

## 4. Git Smart HTTP

- [x] 4.1 Test-first (integration, real git client or JGit client): clone public repo anonymously over `/git/<owner>/<repo>.git`
- [x] 4.2 Mount JGit `GitServlet` via `quarkus-undertow` at `/git/*` with `RepositoryResolver` backed by `GitRepositoryService`
- [x] 4.3 Test-first: push requires auth — anonymous push gets 401, no refs change
- [x] 4.4 Implement HTTP Basic auth filter for `/git/*` accepting personal access tokens; wire `AccessPolicy` (401 unauthenticated private read, 403 no-write push)
- [x] 4.5 Test-first: PAT lifecycle — create (plaintext shown once, hash stored), authenticate, revoke (revoked token → 401)
- [x] 4.6 Implement `AccessToken` generation/revocation service with SHA-256 hashing

## 5. Git SSH Access

- [x] 5.1 Test-first (integration): clone over SSH with registered key succeeds; unknown key rejected
- [x] 5.2 Implement `@ApplicationScoped` `SshServer` lifecycle bean: start on startup, stop on shutdown, configurable port, persistent host key on data volume
- [x] 5.3 Implement `PublickeyAuthenticator` with fingerprint lookup against `ssh_keys` table
- [x] 5.4 Wire `GitPackCommandFactory` to `GitRepositoryService` + `AccessPolicy`; reject non-git commands and shell requests
- [x] 5.5 Test-first: SSH authorization parity — private repo read denied without permission, receive-pack denied without write
- [x] 5.6 Test: host key stable across restarts

## 6. Auth & Accounts (UI side)

- [x] 6.1 Test-first: OIDC login provisions `User` on first login (keyed by `sub`), updates profile on subsequent logins
- [x] 6.2 Configure `quarkus-oidc` code flow, protected UI paths, logout endpoint
- [x] 6.3 Test-first: SSH key management — add valid key (fingerprint computed), reject malformed, reject duplicate, remove key stops SSH auth
- [x] 6.4 Implement SSH key management pages (Qute forms) and PAT management page (create with one-time display, list, revoke)

## 7. Web UI

- [x] 7.1 Test-first: repository list page shows visible repos per user; anonymous sees only public
- [x] 7.2 Implement Qute layout + repository list and create/delete pages (delete with name confirmation)
- [x] 7.3 Test-first: file browser — directory listing at ref, text blob escaped, binary blob download, empty repo shows setup instructions
- [x] 7.4 Implement tree/blob browser with JGit `TreeWalk`; ref selector for branches/tags
- [x] 7.5 Test-first: paginated commit log (abbreviated ID, message, author, date)
- [x] 7.6 Implement commit log (`RevWalk`) and branches/tags pages with default-branch marker
- [x] 7.7 Repository page shows HTTP and SSH clone URLs; mutation controls hidden from non-owners; private repos return not-found/forbidden to unauthorized users

## 8. Native Build Completion & CI

- [x] 8.1 Extend native-image reflection/resource config for all features (Qute templates, OIDC, JGit pack machinery, MINA crypto); native build green
- [ ] 8.2 Native integration smoke tests: HTTP clone+push, SSH clone, OIDC-protected page, Flyway migration on boot
- [x] 8.3 CI pipeline: JVM tests + native build + native smoke tests on main-branch builds
- [x] 8.4 README: setup, configuration (storage root, SSH port, OIDC, Postgres), clone URL schemes, TLS-termination requirement for PAT auth
