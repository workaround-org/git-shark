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
- **Federation (ForgeFed / ActivityPub)** — *opt-in, off by default.* Public repositories are
  exposed as ForgeFed `Repository` actors that remote instances can follow and receive `Push`
  activities from (see below)

## Federation (ForgeFed)

git-shark speaks [ForgeFed](https://forgefed.org) over ActivityPub server-to-server so instances
can interoperate. The first goal is **git-shark ↔ git-shark**: a remote instance follows a public
repository and receives a signed `Push` activity whenever commits land. Standard ActivityStreams +
ForgeFed vocabulary and HTTP Signatures (RSA) are used throughout, so other ForgeFed software *could*
interoperate later (untested).

When enabled, each public repository (and each user) is an actor under `/ap`:

- `GET /ap/repos/{owner}/{name}` — `Repository` actor (also via content negotiation on the repo page
  with `Accept: application/activity+json`); `…/inbox`, `…/outbox`, `…/followers`
- `GET /ap/users/{username}`, `GET /ap/instance` — `Person` / instance `Application` actors
- `GET /.well-known/webfinger?resource=acct:{owner}/{name}@{host}` — actor discovery
- A remote actor `POST`s a signed `Follow` to a repo inbox → recorded + `Accept`'d; `Undo` unfollows
- On push, a `Push` activity is published to the outbox and delivered to followers via a persisted,
  retrying, HTTP-Signature-signed delivery queue

Inbound activities must carry a valid HTTP Signature from an **allowlisted** peer; outbound fetches
are HTTPS-only, allowlist-bound, and SSRF-guarded (no private/loopback/link-local targets). Issues
and pull/merge requests are **not** federated (git-shark has no such features yet).

> Enabling federation publishes **permanent** actor IDs derived from `base-url`. Set a real,
> stable, non-loopback HTTPS origin before turning it on; git-shark refuses to emit actor documents
> otherwise.

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
| `GITSHARK_FEDERATION_ENABLED` | `false` | Master switch for ForgeFed federation |
| `GITSHARK_FEDERATION_BASE_URL` | — | Public HTTPS origin (e.g. `https://shark.example`); actor IDs derive from it and are permanent |
| `GITSHARK_FEDERATION_PEER_ALLOWLIST` | — (empty = deny all) | Comma-separated peer hosts allowed to send/receive federation traffic |
| `GITSHARK_FEDERATION_MAX_ATTEMPTS` | `8` | Max delivery attempts before a queued activity is dead-lettered |

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
| PostgreSQL | `users`, `repositories` (metadata), `ssh_keys` (public keys + fingerprints), `access_tokens` (SHA-256 hashes, labels, last-used), federation tables (`federation_keys`, `remote_actors`, `repository_followers`, `federation_outbox`, `federation_inbox`, `federation_delivery`) |
| Filesystem (`GITSHARK_STORAGE_ROOT`) | Bare Git repositories |
| Filesystem (`GITSHARK_SSH_HOST_KEY`) | SSH host key |

## CI

`.github/workflows/ci.yml`: JVM tests on every push/PR; native build + native smoke tests
gate main-branch builds.
