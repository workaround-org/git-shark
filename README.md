# git-shark 🦈

Self-hosted Git platform (GitHub-like) as a single, natively-compiled Quarkus service.

Bare Git repositories on disk, served over **smart HTTP** (JGit `GitServlet`) and **SSH**
(embedded Apache MINA SSHD), with a server-rendered **Qute** web UI, **OIDC** login,
**PostgreSQL** metadata (Flyway-managed schema), and per-repository access control.

## Features

- Create, browse, and delete personal repositories (public or private)
- Clone/fetch/push over `https://<host>/git/<owner>/<repo>.git`
  - anonymous read on public repositories
  - push and private read authenticate with **personal access tokens** (HTTP Basic password)
- Clone/fetch/push over `ssh://git@<host>:2222/<owner>/<repo>.git`
  - public-key authentication only; keys managed per user in the UI
- Web UI: landing page with login CTA for visitors (`/`), repository list for authenticated users (`/`), public repository browse at `/explore`, file/tree browser, commit log (paginated), branches & tags
- OIDC login (authorization code flow) via `GET /login`; users provisioned on first login
- Single access policy on all paths: owner read/write, public world-readable, private owner-only

## Architecture notes

- Repository names resolve to UUID-based storage paths **through the database only**
  (`<storage-root>/<owner-uuid>/<repo-uuid>.git`); HTTP, SSH, and UI share the same
  resolution and authorization services.
- Tokens are stored as SHA-256 hashes; the plaintext is shown exactly once at creation.
- The SSH host key is generated on first start and persisted, so the host identity is
  stable across restarts.
- SSH serves only `git-upload-pack`/`git-receive-pack`; shells and other commands are rejected.

## Configuration

| Property / Env var | Default | Purpose |
|---|---|---|
| `GITSHARK_STORAGE_ROOT` | `data/repositories` | Root directory for bare repositories (persistent volume) |
| `GITSHARK_SSH_PORT` | `2222` | Embedded SSH server port |
| `GITSHARK_SSH_HOST_KEY` | `data/ssh/host-key` | Persisted SSH host key file |
| `QUARKUS_DATASOURCE_JDBC_URL` / `_USERNAME` / `_PASSWORD` | — (Dev Services in dev/test) | PostgreSQL connection |
| `QUARKUS_OIDC_AUTH_SERVER_URL` / `_CLIENT_ID` / `_CREDENTIALS_SECRET` | — (Keycloak Dev Services in dev/test) | OIDC provider |

> **TLS required in production:** personal access tokens travel as HTTP Basic credentials.
> Terminate TLS in front of the service; never expose plain HTTP publicly.

## Development

```shell script
./mvnw quarkus:dev     # dev mode; PostgreSQL + Keycloak via Dev Services (Docker/Podman required)
./mvnw test            # JVM tests
./mvnw verify -Dnative # native build + integration smoke tests (HTTP health, SSH banner)
```

Native build uses a container build automatically when no local GraalVM is present
(`-Dquarkus.native.container-build=true` to force). Binary: `target/git-shark-1.0-SNAPSHOT-runner`.

## Persisted data

| Store | What |
|---|---|
| PostgreSQL | `users`, `repositories` (metadata), `ssh_keys` (public keys + fingerprints), `access_tokens` (SHA-256 hashes, labels, last-used) |
| Filesystem (`GITSHARK_STORAGE_ROOT`) | Bare Git repositories |
| Filesystem (`GITSHARK_SSH_HOST_KEY`) | SSH host key |

## CI

`.github/workflows/ci.yml`: JVM tests on every push/PR; native build + native smoke tests
gate main-branch builds.
