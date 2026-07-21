# git-shark 🦈

Self-hosted Git platform (GitHub-like) as a single, natively-compiled Quarkus service.

Bare Git repositories on disk, served over **smart HTTP** (JGit `GitServlet`) and **SSH**
(embedded Apache MINA SSHD), with a server-rendered **Qute** web UI, **OIDC** login,
**PostgreSQL** metadata (Flyway-managed schema), and per-repository access control.

## Features

- Create, browse, and delete personal repositories (public or private); the owner can switch a repository between public and private later on its Settings page
- **Organisations** — shared repository namespaces with guest/member/owner roles. An org owns
  repositories exactly like a user (`/repos/<org>/<repo>`, same clone URL forms); org and user
  names share one collision-checked handle namespace. Guests read private org repos, members
  also push, owners manage members and org repositories. Guides:
  [for users](docs/users/organisations.md), [for admins](docs/admins/organisations.md)
- Clone/fetch/push over `https://<host>/git/<owner>/<repo>.git`
  - anonymous read on public repositories
  - push and private read authenticate with **personal access tokens** (HTTP Basic password)
- Clone/fetch/push over SSH — scp shorthand `git@<host>:<owner>/<repo>.git` on the standard
  port, or `ssh://git@<host>:<port>/<owner>/<repo>.git` when the advertised port isn't 22
  - public-key authentication only; keys managed per user in the UI
  - the server binds an unprivileged port (default 2222); the port shown in URLs is set
    separately via `GITSHARK_SSH_EXTERNAL_PORT` (default 22) to match your host mapping
- Web UI: an auth-aware header nav (a "Log in" button for visitors; for signed-in users a top-level Following link plus an Account dropdown holding Profile, SSH keys, Access tokens, and Logout — a JS-free `<details>` menu), landing page with login CTA for visitors (`/`), repository list for authenticated users (`/`), public repository browse at `/explore`, file/tree browser with self-hosted syntax highlighting (extension-based language detection, falls back to plain text for unknown extensions and binary files; Markdown files render to HTML by default with a Rendered/Code toggle), a rendered README (commonmark-java with GFM tables, XSS-safe) shown below the file list on the repository overview page, commit log (paginated), branches, tags (own dedicated page, separate from branches), one-time handle selection (`/onboarding`), profile settings (`/settings/profile`). Every repository sub-page shows a persistent left sidebar with repo identity, a Clone button opening the clone dialog, a pin toggle, and section navigation (Code, Commits, Branches, Tags, Issues, Merge requests, plus Settings for the owner) with per-section counts and active-section highlighting, and the clone panel has copy-to-clipboard buttons for the HTTP and SSH `git clone` commands. Keyboard shortcuts are an optional, progressive enhancement (`?` opens a help overlay, `Escape` closes it, `g h` goes home) — every page works fully without JavaScript
- Per-repository issues: title, optional description (rendered as Markdown, XSS-safe), per-repo sequential number (`#1`, `#2`, …), an author, and an optional assignee (any local user, set by username; blank clears it); created and managed by the repo owner and collaborators, readable by anyone who can read the repo, via a dedicated "New issue" page; title and description can be edited afterwards via an "Edit issue" page. Issue pages are addressed by number (`…/issues/1`); old UUID URLs redirect permanently to the number form
- Issues move through a fixed lifecycle (Planned → In development → Done); the repo navigation shows the open (Planned + In development) issue count, and Done issues collapse into an "Archive" section on the issues page
- Issues auto-close from pushed commit messages, GitHub-style (`close(s|d)`/`fix(es|ed)`/`resolve(s|d)` + `#<number>`, e.g. `fixes #12`), over both HTTP and SSH pushes
- Per-repository merge requests: source → target branch within one repo, with a title, optional description, a per-repo sequential number displayed bang-prefixed (`!1`, `!2`, …, distinct from issues' `#`), an author, and an optional assignee and reviewer (any local user, set by username from a GitHub-style picker that suggests the repo owner, its collaborators, and the repository's top commit authors; blank clears); created and managed by the repo owner and collaborators, readable by anyone who can read the repo, via a dedicated "New merge request" page where the author picks source and target from the repo's branches. Merge-request pages are addressed by number (`…/merge-requests/1`, matching the API and the issue URL scheme); old UUID URLs redirect permanently to the number form
- Merge requests move through the lifecycle Open → Merged / Closed; the repo navigation and left sidebar show the open merge-request count, and merged/closed ones collapse into an "Archive" section on the list page (same pattern as issues)
- Dashboard notifications: the signed-in home page (`/`) surfaces the open issues and merge requests you are involved in — ones you authored, are assigned to, or (for merge requests) are asked to review — each linking straight to the item and labelled with its repository; items in a repository you can no longer read never appear. Built on a pluggable `NotificationSource` aggregation, so future item types can contribute without touching the dashboard
- The merge request detail page renders the live diff of the source branch relative to the merge base with the target (three-dot diff), file by file with per-line add/delete coloring and a changed-files / +additions / −deletions summary — always computed live from git, never duplicated into the database
- The owner or a collaborator can Merge or Close an open merge request from the detail page; merging runs entirely in-core against the bare repository (no working tree), fast-forwarding when possible or else recording a two-parent merge commit authored by the acting user and advancing the target branch ref. An automatic merge that would conflict is rejected; a source branch already contained in the target is treated as already merged
- Comments on issues and merge requests: any authenticated user who can read the repository can post a free-text comment to an issue's or a merge request's discussion thread (a flat, chronological list showing each comment's author and time), from the item's detail page. A comment can be deleted by its author, the repository owner, or a collaborator. These general discussion comments are distinct from a merge request's per-line review comments below
- Per-commit diff view: from the commits tab, clicking a commit opens its detail page showing the diff that commit introduced — file by file with add/delete coloring and a changed-files / +additions / −deletions summary, computed live from git (diff against the commit's first parent, or the empty tree for a root commit)
- Line-level review comments on a merge request's diff: any authenticated user who can read the repository can comment on a specific diff line (added, deleted, or context) from the merge-request detail page; comments render inline beneath the line they anchor to. A comment can be deleted by its author, the repository owner, or a collaborator. Comments are anchored to a file plus the diff line's old/new line numbers and must land on a line that's part of the current diff. Hovering a commentable line reveals a comment icon on the right; clicking it opens the form inline — a progressive-enhancement disclosure that works without JavaScript
- OIDC login (authorization code flow) via `GET /login`; on first login the user account is created without a username and the browser is redirected to `/onboarding`, where the user picks a URL-safe handle (`^[a-z0-9][a-z0-9-]{0,38}$`, unique across users and organisations). The chosen handle — not the OIDC `preferred_username` claim (which is an SPN form in kanidm and not URL-safe) — is used in all repo, SSH, ActivityPub, and webfinger URLs. The `name` claim becomes an editable display name; both can be changed later at `/settings/profile`. A request filter blocks all app pages until a handle is chosen. The code-flow callback is pinned to `/login` (`redirect-path` + `restore-path-after-redirect`, so strict-`redirect_uri` IdPs like kanidm register one URI) and after login the user lands back on the page they came from — the header's Log in button carries `?redirect=<current page>` (local paths only, open-redirect guarded). Expired ID tokens are refreshed silently with the refresh token (`refresh-expired=true`, 60 s proactive skew, session cookie usable 12 h past expiry) instead of logging the user out. Logout is local-session only via `POST /logout` (the kanidm provider advertises no `end_session_endpoint`, so RP-Initiated Logout is disabled)
- Profile pictures: users can upload a PNG/JPEG/GIF/WebP avatar (≤ 2 MB, content-type and magic bytes both validated) at `/settings/profile`, stored on the filesystem keyed by user UUID and served publicly at `GET /users/{username}/avatar`; shown wherever a local user is rendered (header nav, repo lists, repo sidebar, issue/MR/comment authors) via a reusable Qute avatar tag, removable, and falling back to an initials badge when absent. Git commit authors and remote federation actors are not local users and keep their existing pseudo-avatars
- Per-user content width setting (Appearance section on `/settings/profile`): **Full** (default — repository pages span the entire screen, other pages cap at 1400px), **Comfortable (fixed 1400px)**, or **Compact (fixed 1120px)** on every page; the width also aligns the header nav content (the bar background stays edge-to-edge), and windows narrower than the cap just fill the screen. Guide: [profile settings](docs/users/profile.md)
- **Collaborators** — the repository owner can grant other local users read+write access
  (one flat role) on a per-repository settings page (`…/settings/collaborators`, reached from a
  "Manage collaborators" link on the owner-only repository Settings page). Collaborators can read and push — UI, HTTP, and SSH alike — even
  on private repositories, and manage issues and merge requests; deleting the repository,
  managing mirrors, and managing collaborators stay owner-only. Guides:
  [for users](docs/users/collaborators.md), [for admins](docs/admins/collaborators.md)
- Per-repository images: the repo owner can upload a custom image (same PNG/JPEG/GIF/WebP, ≤ 2 MB, validated rules as avatars) on a dedicated owner-only repository **Settings** page (`/repos/{owner}/{name}/settings`), stored on the filesystem keyed by repo UUID. It replaces the owner's avatar wherever the repository is shown (repo lists, repo sidebar); a repository with no custom image falls back to its owner's avatar. Served at `GET /repos/{owner}/{name}/image`, visibility-guarded so a private repo's image never leaks (`404` for non-viewers), and removable back to the fallback
- **Forks** — any logged-in user can fork a repository they can read into their own namespace with
  one click (or `POST /api/v1/repos/{owner}/{name}/fork`). The fork carries the source's name,
  visibility, and description, copies every branch and tag via a local bare clone, and records a
  `forked from {owner}/{name}` link back to its parent. A private source can only be forked by
  someone who can already read it, so a fork never exposes it. Guides:
  [for users](docs/users/forking.md), [for admins](docs/admins/forking.md),
  [architecture](docs/maintainers/forking.md)
- **Search** — a simple case-insensitive substring search over repositories (owner, name,
  description) and people (username, display name), from the header search box or `GET /search`,
  and as JSON at `GET /api/v1/search?q=<term>`. Repository hits obey the same visibility rule as
  everywhere else, so a private repo never surfaces to someone who cannot already read it. Person
  hits link to that user's public profile page at `GET /users/{username}` (avatar, display name,
  and their visibility-filtered repositories — the person equivalent of the `/orgs/{name}`
  organisation profile). Guides: [for users](docs/users/search.md), [for admins](docs/admins/search.md)
- Single access policy on all paths: owner read/write, collaborators read/write, org roles
  (guest read / member write / owner admin) on org repositories, public world-readable,
  private repositories visible only to whoever holds a read grant
- **JSON REST API** under `/api/v1`, authenticated with the same personal access tokens as
  git-over-HTTP (`Authorization: Bearer <token>` or the Gitea-style `Authorization: token <token>`),
  auto-documented via OpenAPI/Swagger UI. The contract is **Gitea-compatible**, so Gitea tooling
  (Renovate, `tea`) can drive it (see below)
- **MCP server** at `/mcp` (Streamable HTTP), exposing the same feature set as the REST API as
  MCP tools so an AI client can manage repositories, issues, merge requests, and MR line-comments
  (see below)
- **Push mirrors** — the repository owner can replicate a repository to external remotes on
  every push (`git push --mirror` semantics, all refs including deletions), over HTTPS with
  stored credentials or over SSH with a server-generated Ed25519 deploy key. Syncs run
  asynchronously (the incoming push never waits or fails because of a mirror), coalesce under
  rapid pushes, retry with exponential backoff, and dead-letter with the error visible on the
  owner-only repository **Settings** page where mirrors are managed; secrets are encrypted at
  rest (`GITSHARK_SECRET_KEY`). Guides:
  [for users](docs/users/mirrors.md), [for admins](docs/admins/mirrors.md),
  [architecture](docs/maintainers/push-mirrors.md)
- **Federation (ForgeFed / ActivityPub)** — *opt-in, off by default.* Public repositories are
  exposed as ForgeFed `Repository` actors that remote instances can follow and receive `Push`
  activities from (see below)
- **CI/CD runners** — *phase 1: registration only.* git-shark implements the server side of the
  Forgejo/Gitea `runner.v1` Connect protocol under `/api/actions`, so a stock `forgejo-runner` /
  `act_runner` registers and reports in unchanged. Instance admins (handles in
  `GITSHARK_ADMIN_HANDLES`) generate reusable registration tokens and manage runners at
  `/admin/runners`; secrets are stored hashed. Workflow execution (fetching and running jobs) is a
  follow-up phase. Guides: [for users](docs/users/ci-runners.md), [for admins](docs/admins/ci-runners.md),
  [architecture](docs/maintainers/ci-runners.md)
  activities from; local users can in turn follow a remote repository — or a whole remote user, whose
  public repositories are then followed and shown grouped — and read their pushes (see below)

## Federation (ForgeFed)

git-shark speaks [ForgeFed](https://forgefed.org) over ActivityPub server-to-server so instances
can interoperate. The first goal is **git-shark ↔ git-shark**: a remote instance follows a public
repository and receives a signed `Push` activity whenever commits land. Standard ActivityStreams +
ForgeFed vocabulary and HTTP Signatures (RSA) are used throughout, so other ForgeFed software *could*
interoperate later (untested). Detailed guides: [for users](docs/users/federation.md),
[for deployment](docs/admins/federation.md), and
[architecture notes for maintainers](docs/maintainers/forgefed.md).

When enabled, each public repository (and each user) is an actor under `/ap`:

- `GET /ap/repos/{owner}/{name}` — `Repository` actor (also via content negotiation on the repo page
  with `Accept: application/activity+json`); `…/inbox`, `…/outbox`, `…/followers`
- `GET /ap/users/{username}`, `GET /ap/instance` — `Person` / instance `Application` actors
- `GET /.well-known/webfinger?resource=acct:{owner}/{name}@{host}` — actor discovery
- A remote actor `POST`s a signed `Follow` to a repo inbox → recorded + `Accept`'d; `Undo` unfollows
- On push, a `Push` activity is published to the outbox and delivered to followers via a persisted,
  retrying, HTTP-Signature-signed delivery queue
- Outbound: logged-in users can follow a remote repository/actor from the **Following** page by
  handle (`owner/name@host`) or actor URL; the handle is resolved via WebFinger (lenient about
  bare-host vs. host:port `acct:` forms) and a signed `Follow` is enqueued for delivery, tracked as
  PENDING until the remote `Accept`s (unfollow sends `Undo`)
  - Inbound `Push` activities from followed repositories are stored and surfaced as a "Recent
    pushes" feed on the Following page; pushes from actors nobody follows are dropped

Inbound activities must carry a valid HTTP Signature from an **allowlisted** peer; outbound fetches
are HTTPS-only, allowlist-bound, and SSRF-guarded (no private/loopback/link-local targets). Issues
and merge requests exist locally but are **not** federated yet.

> Enabling federation publishes **permanent** actor IDs derived from `base-url`. Set a real,
> stable, non-loopback HTTPS origin before turning it on; git-shark refuses to emit actor documents
> otherwise.

## REST API

A JSON REST API is served under `/api/v1`, auto-documented via the existing
`quarkus-smallrye-openapi` extension (`GET /q/openapi`, `GET /q/swagger-ui`). The surface is being
migrated to a **Gitea-compatible contract** so Gitea tooling (Renovate, `tea`) can consume it
unchanged; `GET /api/v1/version` reports the emulated Gitea version (`GITSHARK_GITEA_API_VERSION`).

- Authenticated with the **same personal access tokens** used for git-over-HTTP, sent as either
  `Authorization: Bearer <token>` or the Gitea-style `Authorization: token <token>` (not HTTP Basic)
- Anonymous requests are allowed for public reads only; mutations require a token and write
  access (owner or collaborator; posting a comment only requires read access, deleting a
  repository stays owner-only)
- Same visibility rules as the UI: private repositories are hidden as `404`, not `403`

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/version` | Emulated Gitea version (anonymous; gates Gitea-client feature use) |
| GET | `/api/v1/user` | The token owner in Gitea user shape (`401` without a valid token) |
| GET | `/api/v1/repos` | Repositories visible to the caller (Gitea repository objects) |
| POST | `/api/v1/repos` | Create a repository (`400` invalid name, `409` duplicate) |
| GET | `/api/v1/repos/{owner}/{name}` | Repository detail in Gitea shape (`full_name`, `default_branch`, `clone_url`, `permissions`, …) |
| DELETE | `/api/v1/repos/{owner}/{name}` | Delete a repository (owner only) |
| GET, POST | `/api/v1/repos/{owner}/{name}/issues` | List / create issues |
| GET, PATCH, DELETE | `/api/v1/repos/{owner}/{name}/issues/{number}` | Get / update status / delete an issue |
| GET, POST | `/api/v1/repos/{owner}/{name}/merge-requests` | List / create merge requests |
| GET | `/api/v1/repos/{owner}/{name}/merge-requests/{number}` | Merge request detail |
| POST | `/api/v1/repos/{owner}/{name}/merge-requests/{number}/merge` | Merge |
| POST | `/api/v1/repos/{owner}/{name}/merge-requests/{number}/close` | Close |
| GET, POST | `/api/v1/repos/{owner}/{name}/merge-requests/{number}/comments` | List / add line-level review comments (any reader may comment) |
| DELETE | `/api/v1/repos/{owner}/{name}/merge-requests/{number}/comments/{commentId}` | Delete a comment (author, repo owner, or collaborator) |

## MCP server

An MCP (Model Context Protocol) server is exposed via the Quarkiverse extension
`io.quarkiverse.mcp:quarkus-mcp-server-http` (1.13.1), mirroring the REST API's feature set as
MCP tools. Client setup (Claude Code, Claude Desktop, others) is described in
[docs/users/mcp.md](docs/users/mcp.md).

- **Transport:** Streamable HTTP at `/mcp` (the extension also serves the legacy HTTP/SSE
  variant, but Streamable HTTP is the one git-shark uses)
- **Auth:** same personal access tokens as the REST API, sent as `Authorization: Bearer <token>`
  on the MCP request; read tools work anonymously for public repos, write tools require a token
  and owner or collaborator rights (commenting only requires read access)

| Area | Tools |
|---|---|
| Repositories | `listRepositories`, `getRepository`, `createRepository`, `forkRepository`, `deleteRepository` |
| Issues | `listIssues`, `getIssue`, `createIssue`, `updateIssue`, `assignIssue`, `updateIssueStatus`, `deleteIssue` |
| Merge requests | `listMergeRequests`, `getMergeRequest`, `createMergeRequest`, `mergeMergeRequest`, `closeMergeRequest`, `listMergeRequestComments`, `addMergeRequestComment` |
| User | `currentUser` |

## Architecture notes

- Repository names resolve to UUID-based storage paths **through the database only**
  (`<storage-root>/<owner-uuid>/<repo-uuid>.git`); HTTP, SSH, and UI share the same
  resolution and authorization services.
- Tokens are stored as SHA-256 hashes; the plaintext is shown exactly once at creation, with a copy-to-clipboard button next to it.
- The SSH host key is generated on first start and persisted, so the host identity is
  stable across restarts.
- SSH serves only `git-upload-pack`/`git-receive-pack`; shells and other commands are rejected.
- UI fonts (Space Grotesk, JetBrains Mono) are self-hosted from the application origin — no CDN or other external requests, so the UI works fully offline/air-gapped.

## Configuration

| Property / Env var | Default | Purpose |
|---|---|---|
| `GITSHARK_STORAGE_ROOT` | `data/repositories` | Root directory for bare repositories (persistent volume) |
| `GITSHARK_AVATAR_ROOT` | `data/avatars` | Root directory for uploaded profile pictures (persistent volume) |
| `GITSHARK_REPO_IMAGE_ROOT` | `data/repo-images` | Root directory for uploaded per-repository images (persistent volume) |
| `GITSHARK_SSH_PORT` | `2222` | Embedded SSH server port |
| `GITSHARK_SSH_HOST_KEY` | `data/ssh/host-key` | Persisted SSH host key file |
| `GITSHARK_ADMIN_HANDLES` | — (empty = no admins) | Comma-separated handles allowed into `/admin/*` (CI runner management) |
| `QUARKUS_DATASOURCE_JDBC_URL` / `_USERNAME` / `_PASSWORD` | — (Dev Services in dev/test) | PostgreSQL connection |
| `QUARKUS_OIDC_AUTH_SERVER_URL` / `_CLIENT_ID` / `_CREDENTIALS_SECRET` | — (Keycloak Dev Services in dev/test) | OIDC provider |
| `QUARKUS_OIDC_AUTHENTICATION_STATE_SECRET` | — (dev/test use a fixed dev secret) | Encrypts the OIDC state cookie carrying the PKCE `code_verifier`; ≥ 32 chars, stable across pods |
| `QUARKUS_OIDC_TOKEN_STATE_ENCRYPTION_SECRET` | — (falls back to credentials secret) | Encrypts the post-login session cookie holding the tokens; ≥ 32 chars, set explicitly for multi-pod |
| `GITSHARK_SECRET_KEY` | — | Symmetric key encrypting push-mirror secrets at rest (AES-256-GCM); required to create mirrors, keep it stable |
| `GITSHARK_MIRROR_MAX_ATTEMPTS` | `8` | Max attempts per mirror sync before it is dead-lettered |
| `GITSHARK_MIRROR_ALLOW_INSECURE` | `false` | **Dev/local only.** Allow `http://` and loopback/private mirror targets. Never enable in production. |
| `GITSHARK_FEDERATION_ENABLED` | `false` | Master switch for ForgeFed federation |
| `GITSHARK_FEDERATION_BASE_URL` | — | Public HTTPS origin (e.g. `https://shark.example`); actor IDs derive from it and are permanent |
| `GITSHARK_FEDERATION_PEER_ALLOWLIST` | — (empty = deny all) | Comma-separated peer hosts allowed to send/receive federation traffic |
| `GITSHARK_FEDERATION_MAX_ATTEMPTS` | `8` | Max delivery attempts before a queued activity is dead-lettered |
| `GITSHARK_FEDERATION_USER_RESYNC_INTERVAL` | `5m` | How often followed remote users are re-scanned for new public repositories |
| `GITSHARK_FEDERATION_DEV_ALLOW_INSECURE` | `false` | **Dev/local only.** Lets the SSRF guard accept `http` + loopback/private targets so two instances can federate on one machine (peer allowlist still enforced). Never enable in production. |

> **TLS required in production:** personal access tokens travel as HTTP Basic credentials
> (git-over-HTTP) or a `Bearer` token (REST API). Terminate TLS in front of the service; never
> expose plain HTTP publicly.

## Development

```shell script
./mvnw quarkus:dev     # dev mode; PostgreSQL + Keycloak via Dev Services (Docker/Podman required)
./mvnw test            # JVM tests
./mvnw verify -Dnative # native build + integration tests (HTTP, Git protocol, SSH)
```

Native build uses a container build automatically when no local GraalVM is present
(`-Dquarkus.native.container-build=true` to force). Binary: `target/git-shark-1.0-SNAPSHOT-runner`.
Native-specific fixes: JGit's NLS classes (`JGitText`, `HttpServerText`) and its config enums are
registered in `META-INF/native-image/reflect-config.json`, and BouncyCastle is registered at build
time via `quarkus.security.security-providers=BC` (Apache SSHD's runtime provider registration
doesn't work in native images).

Integration tests (Failsafe, `*IT`) run against the packaged app — JVM jar or native binary alike:

- `SmokeIT` — health endpoint, landing page, explore page, OpenAPI document, SSH banner
- `GitHttpIT` — anonymous JGit clone of the seeded `alice/demo` repo over smart HTTP, anonymous
  push rejection (refs unchanged), repository overview page, raw file serving
- `SshHandshakeIT` — full SSH handshake (KEX/host key/cipher via Apache SSHD + BouncyCastle),
  asserting clean `publickey`-auth rejection for an unregistered key

ITs seed demo data (`GITSHARK_DEV_SEED_DATA=true`) and isolate runtime data under
`target/it-data` (see Failsafe `systemPropertyVariables` in `pom.xml`).

### Dev mode seed data

Two `%dev`-only flags are set in `application.properties` (both default `false` in all other profiles):

- `gitshark.dev.seed-data=true` — on startup, `DevDataSeeder` idempotently creates user `alice` owning a public repository `demo` with a Markdown README on `main`, an open merge request (`feature` → `main`, with a sample review comment) and four issues in different states with short and long Markdown descriptions. A fresh dev instance is never empty.
- `gitshark.dev.adopt-username=true` — Keycloak Dev Services mint a fresh OIDC subject on every run, so the seeded `alice` row would otherwise collide on login. With this flag, an unknown subject whose username matches an existing account re-keys that account to the new subject instead of rejecting the login. **Never enabled in production** (re-keying from a username claim is an account-takeover vector).

Dev/test Keycloak imports `src/main/resources/quarkus-realm.json` (users `alice`/`alice` and
`bob`/`bob`, client `quarkus-app` with the `profile` and `email` client scopes). The app requests
`quarkus.oidc.authentication.scopes=profile,email`, so the ID token carries `preferred_username`,
`name` and `email` — logging in as `alice` adopts the seeded account directly and skips the
onboarding page.

## Persisted data

| Store | What |
|---|---|
| PostgreSQL | `users`, `repositories` (metadata), `repository_pins` (per-user pinned repositories), `ssh_keys` (public keys + fingerprints), `access_tokens` (SHA-256 hashes, labels, last-used), push-mirror tables (`push_mirror` with AES-GCM-encrypted secrets, `mirror_sync` queue), federation tables (`federation_keys`, `remote_actors`, `repository_followers`, `remote_follows`, `remote_user_follows`, `received_pushes`, `federation_outbox`, `federation_inbox`, `federation_delivery`) |
| Filesystem (`GITSHARK_STORAGE_ROOT`) | Bare Git repositories |
| Filesystem (`GITSHARK_AVATAR_ROOT`) | Uploaded profile pictures, one file per user (UUID-named) |
| Filesystem (`GITSHARK_SSH_HOST_KEY`) | SSH host key |

## CI

- `.github/workflows/jvm.yml` — JVM tests on every push/PR; on main, builds and pushes the
  JVM container image to GHCR as a multi-arch manifest (linux/amd64 + linux/arm64, via
  Buildx/QEMU), and runs a SonarQube Community Build analysis against `sonar.ha1nz.de`
  (test coverage via the combined JaCoCo report at `target/jacoco-report/jacoco.xml`).
  Community Build analyses the main branch only, so the `sonar` job is gated to main.
- `.github/workflows/native.yml` — native build + integration tests on every push/PR; on
  main, builds and pushes the native container image to GHCR with a `-native` tag suffix
  (linux/amd64 only).
