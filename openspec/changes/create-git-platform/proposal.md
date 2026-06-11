## Why

We want a self-hosted Git platform (GitHub-like) as a single, lightweight, natively-compiled Quarkus service — no JVM
footprint, fast startup, simple ops. Existing platforms (Gitea, GitLab) are either non-JVM or heavyweight; building on
Quarkus + JGit gives us full control and integrates with our existing OIDC/Postgres infrastructure.

## What Changes

- Bootstrap a new Quarkus service (`git-shark`) that hosts bare Git repositories on disk
- Serve Git smart HTTP protocol (clone/fetch/push) via JGit's `GitServlet`, mounted as a servlet
- Serve Git over SSH via Apache MINA SSHD + `sshd-git`, with public-key authentication
- Server-rendered web UI with Qute: repository list, file browser, commit log, branch/tag views
- User accounts via OIDC (login through external identity provider); SSH keys managed per user
- Repository and user metadata persisted in PostgreSQL, schema managed by Flyway migrations
- Access control: repository ownership and read/write permissions enforced on HTTP, SSH, and UI paths
- Whole service must compile to a GraalVM native image (native-image-compatible dependency usage, reflection config
  where JGit/MINA need it)

## Capabilities

### New Capabilities

- `repo-management`: Create, list, and delete repositories; bare repo storage on the filesystem; metadata (name, owner,
  visibility, description) in PostgreSQL
- `git-smart-http`: Clone, fetch, and push over HTTP(S) using the Git smart protocol via JGit `GitServlet`, with
  authentication and per-repo authorization
- `git-ssh-access`: Clone, fetch, and push over SSH using Apache MINA SSHD + `sshd-git`, authenticated by
  user-registered public keys
- `web-ui`: Qute-rendered web interface — repository overview, file/tree browser, commit history, branch and tag listing
- `auth-accounts`: OIDC-based login and user provisioning, session handling, SSH public key management per user
- `native-build`: GraalVM native image build of the full service, including reflection/resource config for JGit, MINA
  SSHD, and Qute

### Modified Capabilities

(none — greenfield project)

## Impact

- New Quarkus project (bootstrapped by the user) — all code is new
- Dependencies: `quarkus-qute`, `quarkus-rest`/`quarkus-undertow` (servlet support for `GitServlet`), `quarkus-oidc`,
  `quarkus-jdbc-postgresql`, `quarkus-hibernate-orm-panache` (Panache Next), `quarkus-flyway`, `org.eclipse.jgit`,
  `org.eclipse.jgit.http.server`, `org.apache.sshd:sshd-core` + `sshd-git`
- Infrastructure: PostgreSQL database, external OIDC provider, persistent volume for repository storage, exposed SSH
  port in addition to HTTP
- Native image: JGit and MINA SSHD are not Quarkus extensions — reflection, JNI, and resource configuration must be
  maintained manually; this constrains dependency choices throughout
