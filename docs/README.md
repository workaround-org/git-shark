# git-shark documentation 🦈

Self-hosted Git platform as a single, natively-compiled Quarkus service — bare Git
repositories on disk served over smart HTTP and SSH, a server-rendered Qute web UI,
OIDC login, and PostgreSQL metadata.

## Contents

### For users

- **[Profile settings](users/profile.md)** — change your username and display
  name, upload or remove a profile picture.
- **[Federation](users/federation.md)** — follow public repositories on other
  instances, the push feed, your federated identity.

### For admins

- **[Getting Started](admins/getting-started.md)** — deploy git-shark with Docker
  Compose, from zero to a running instance behind TLS with OIDC login and SSH git
  access.
- **[Federation](admins/federation.md)** — enable and operate ForgeFed federation:
  configuration, allowlist, reverse-proxy requirements, delivery queue, monitoring.

### For maintainers

- **[Avatars](maintainers/avatars.md)** — profile-picture storage, validation,
  and rendering, plus what's covered and what's out of scope.
- **[ForgeFed architecture](maintainers/forgefed.md)** — how federation is
  implemented, the decisions behind it, what works and what is still missing.

## Where else to look

- [`../README.md`](../README.md) — feature overview and architecture notes.
- Application configuration lives in `src/main/resources/application.properties`.
  Every production knob is overridable via an environment variable (listed in
  [Getting Started → Configuration reference](admins/getting-started.md#configuration-reference)).
