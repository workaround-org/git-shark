# git-shark documentation 🦈

Self-hosted Git platform as a single, natively-compiled Quarkus service — bare Git
repositories on disk served over smart HTTP and SSH, a server-rendered Qute web UI,
OIDC login, and PostgreSQL metadata.

## Contents

### For users

- **[Profile settings](users/profile.md)** — change your username and display
  name, upload or remove a profile picture.
- **[Repository image](users/repository-image.md)** — give a repository its own
  picture instead of your avatar, from its owner-only Settings page.
- **[Repository visibility](users/repository-visibility.md)** — what public and
  private mean, and how the owner switches between them on the Settings page.
- **[Federation](users/federation.md)** — follow public repositories on other
  instances, the push feed, your federated identity.
- **[Push mirrors](users/mirrors.md)** — replicate a repository to an external
  remote on every push: HTTPS vs SSH setup, deploy keys, status and
  troubleshooting.
- **[Collaborators](users/collaborators.md)** — grant other users read and
  write access to your repository, what collaborators can and cannot do.
- **[Organisations](users/organisations.md)** — shared repository namespaces
  with guest/member/owner roles: creating an org, managing members, org
  repositories.
- **[AI clients (MCP)](users/mcp.md)** — connect Claude Code, Claude Desktop, or
  any MCP client to your instance: token setup, client configuration, available
  tools.

### For admins

- **[Getting Started](admins/getting-started.md)** — deploy git-shark with Docker
  Compose, from zero to a running instance behind TLS with OIDC login and SSH git
  access.
- **[Persistent data](admins/persistent-data.md)** — every store that must survive
  container recreation (database, repositories, avatars, SSH host key), what breaks
  when each is lost, and how to retrofit older deployments.
- **[Federation](admins/federation.md)** — enable and operate ForgeFed federation:
  configuration, allowlist, reverse-proxy requirements, delivery queue, monitoring.
- **[Push mirrors](admins/mirrors.md)** — the secret key, outbound network and
  SSRF behavior, the sync queue, and the tables involved.
- **[Collaborators](admins/collaborators.md)** — access semantics, endpoints,
  and the `repository_collaborators` table (no configuration needed).
- **[Organisations](admins/organisations.md)** — shared handle namespace, owner
  resolution, role semantics, endpoints, and the `organisations` /
  `organisation_members` tables (no configuration needed).

### For maintainers

- **[Avatars](maintainers/avatars.md)** — profile-picture storage, validation,
  and rendering, plus what's covered and what's out of scope.
- **[Repository images](maintainers/repo-images.md)** — per-repository image
  storage, how it reuses the avatar machinery, owner-avatar fallback, and the
  visibility-guarded serving endpoint.
- **[ForgeFed architecture](maintainers/forgefed.md)** — how federation is
  implemented, the decisions behind it, what works and what is still missing.
- **[Push mirrors architecture](maintainers/push-mirrors.md)** — trigger flow,
  queue design, credential encryption, and SSH decisions behind push mirroring.

## Where else to look

- [`../README.md`](../README.md) — feature overview and architecture notes.
- Application configuration lives in `src/main/resources/application.properties`.
  Every production knob is overridable via an environment variable (listed in
  [Getting Started → Configuration reference](admins/getting-started.md#configuration-reference)).
