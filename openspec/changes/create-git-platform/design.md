## Context

Greenfield project. The user bootstraps the Quarkus project skeleton; this change implements the full first iteration of
`git-shark`, a self-hosted Git platform. The hard constraints shaping the design:

- **Native image is the goal.** Every dependency choice must survive GraalVM native compilation. JGit, MINA SSHD, and
  BouncyCastle are not Quarkus extensions, so reflection/resource/JNI config is on us.
- **JGit's `GitServlet` requires the Servlet API**, which in Quarkus means `quarkus-undertow` alongside Quarkus REST.
- **SSH transport runs outside HTTP** — MINA SSHD opens its own port and lifecycle, managed as a CDI bean.
- Company standards apply: Panache Next, `WithId.AutoUUID` entity IDs, Flyway migrations, OIDC, test-first development.

## Goals / Non-Goals

**Goals:**

- Host bare Git repositories with clone/fetch/push over smart HTTP and SSH
- OIDC login, per-user SSH keys, per-repo owner/visibility access control
- Qute server-rendered UI: repo list, file browser, commit log, branches/tags
- PostgreSQL persistence with Flyway-managed schema
- Buildable and runnable as a GraalVM native image

**Non-Goals (this change):**

- Pull/merge requests, issues, code review, webhooks, CI integration
- Organizations/teams — only personal repositories owned by a single user
- Repository forks, mirrors, LFS, submodule UI
- HTTP password/token auth beyond what OIDC provides (see Decisions)
- Federation, replication, HA — single instance, single storage volume

## Decisions

### D1: Servlet container via `quarkus-undertow` for `GitServlet`

JGit's smart HTTP implementation (`org.eclipse.jgit.http.server.GitServlet`) is a plain `HttpServlet`. Quarkus supports
servlets through `quarkus-undertow`; we register `GitServlet` with `@WebServlet` (or a `ServletExtension`) under
`/git/*`. URL scheme: `https://host/git/<owner>/<repo>.git`.
*Alternative considered:* reimplementing upload-pack/receive-pack on Quarkus REST with JGit internals — more
native-friendly in theory, but re-derives protocol plumbing JGit already ships and tests. Rejected.

### D2: SSH via Apache MINA SSHD + `sshd-git`, run as CDI-managed server

`sshd-git` provides `GitPackCommandFactory` that bridges SSH exec channels (`git-upload-pack` / `git-receive-pack`) to
JGit.

**Deviation (found during implementation):** `sshd-git`'s `GitPackCommandFactory` resolves the repository as
`rootDir + path-from-command`, which cannot map `<owner>/<repo>.git` names onto our UUID-based storage layout
(D3: resolution must go through the database). It is also compiled against JGit 5.13 while we run JGit 7.x. We
therefore implement our own small MINA `CommandFactory` that parses `git-upload-pack`/`git-receive-pack`, resolves
and authorizes through `GitRepositoryService`/`AccessPolicy`, and streams to JGit's `UploadPack`/`ReceivePack`
directly. The `sshd-git` dependency is dropped; only `sshd-core` remains. An `@ApplicationScoped` bean starts `SshServer` on `@Startup` and stops it on shutdown, port configurable (default
2222 — native binary shouldn't require root for 22; ops can remap). `PublickeyAuthenticator` resolves the presented key
against stored user keys (fingerprint lookup in Postgres). Host key is persisted to the data volume on first start (
`SimpleGeneratorHostKeyProvider` writes the key file) so the host identity is stable across restarts.
*Alternative considered:* external sshd + forced command (gitolite-style). Rejected — breaks the single-binary goal.

### D3: Repository storage layout and resolution

Bare repos live at `<storage-root>/<owner-uuid>/<repo-uuid>.git`; UUID-based paths avoid rename problems and
path-injection from user-chosen names. Resolution from `<owner>/<repo>` names to filesystem paths goes through the
database (single source of truth). A `RepositoryResolver` (HTTP) and the SSH command factory share one
`GitRepositoryService` so both transports resolve and authorize identically.

### D4: AuthN/AuthZ split per transport

- **UI:** `quarkus-oidc` authorization code flow; users are provisioned/updated in Postgres on first login (keyed by
  OIDC `sub`).
- **Smart HTTP git ops:** same OIDC tenant, but git clients can't do code flows — they send HTTP Basic. We accept
  personal access tokens (generated in UI, stored hashed) as Basic password. Public repos allow anonymous read (
  upload-pack); push always requires auth.
- **SSH:** public-key auth only.
- **Authorization (all paths):** owner has read/write; public repos world-readable; private repos owner-only. One
  `AccessPolicy` service used by UI, HTTP filter, and SSH command factory.
  *Alternative considered:* OIDC bearer tokens for git HTTP — git credential helpers handle static tokens far better;
  PATs are the pragmatic GitHub-proven choice.

### D5: Persistence — Panache Next, UUID IDs, Flyway

Entities: `User` (oidcSub, username, displayName, email), `Repository` (name, owner FK, visibility, description),
`SshKey` (user FK, title, publicKey, fingerprint), `AccessToken` (user FK, tokenHash, label, lastUsed). All UUID ids.
Flyway `V1__init.sql` creates the schema; no Hibernate DDL generation in prod.

**Deviation (found during implementation):** `WithId.AutoUUID` is broken in Panache Next 3.36.2 — its generic
`@GeneratedValue IdType id` field resolves to a *sequence* generator, failing at runtime with
`IdentifierGenerationException: Unknown integral data type for ids : java.util.UUID`. Entities therefore declare
`@Id @GeneratedValue(strategy = GenerationType.UUID) public UUID id` explicitly and implement `PanacheEntity.Managed`
directly. Same UUID semantics, no supertype. Revisit when the upstream bug is fixed.

### D6: UI — Qute type-safe templates, no JS framework

Server-rendered pages with Qute (`@CheckedTemplate`), HTML forms for mutations. Repo browsing reads live from JGit (
`TreeWalk` for trees/blobs, `RevWalk` for commit log) — no Git data duplicated into Postgres. Pagination on commit log.
Binary blobs render as download links; text blobs as escaped `<pre>` (syntax highlighting deferred).

### D7: Native image strategy

- Register JGit + MINA SSHD reflection/resources via `reflect-config.json`/`resource-config.json` under
  `src/main/resources/META-INF/native-image/` (or `@RegisterForReflection` holders where simpler).
- Use MINA SSHD's BouncyCastle-free path where possible (Ed25519 via `net.i2p.crypto:eddsa` or JDK 17+ built-in
  algorithms) to dodge BC's heavy native-image friction; if BC proves unavoidable, register it explicitly.
- JGit: avoid `JSch`-era transports; only `http.server` + core packfile machinery are on the hot path, both
  reflection-light.
- CI verifies native build (`-Dnative`) plus a smoke test: native binary boots, clone over HTTP and SSH succeeds.

## Risks / Trade-offs

- [JGit/MINA native-image incompatibilities surface late] → Stand up the native build + clone/push smoke test in the
  very first implementation tasks, not at the end; fail fast while the dependency surface is small.
- [PAT over Basic is plaintext on the wire] → Document TLS-termination requirement; tokens stored only as SHA-256
  hashes; revocable in UI.
- [Large repo operations (clone of big packs) blow memory in native image] → JGit streams packs; set sane `http`/
  `receive-pack` limits (`max-object-size`), make container memory configurable, load-test with a large repo before
  release.
- [SSH port 2222 vs user expectation of 22] → Documented; deployment can remap via service/load balancer.
- [UUID storage paths make on-disk debugging harder] → Admin endpoint/CLI mapping name → path; acceptable for integrity
  gain.
- [Anonymous read on public repos enables scraping] → Acceptable for v1; rate limiting deferred.

## Migration Plan

Greenfield — no migration. Deploy order: Postgres up → Flyway migrates on boot → service starts HTTP (8080) + SSH (
2222). Rollback = redeploy previous image; Flyway `V1` only, no destructive migrations in this change.

## Open Questions

- Which OIDC provider in dev? (Keycloak Dev Services assumed for tests.)
- Repo size quotas — defer or enforce minimal global limit in v1?
- Should repo names be unique globally or per-owner? (Design assumes per-owner: `<owner>/<repo>`.)
