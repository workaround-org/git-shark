# git-shark documentation 🦈

Self-hosted Git platform as a single, natively-compiled Quarkus service — bare Git
repositories on disk served over smart HTTP and SSH, a server-rendered Qute web UI,
OIDC login, and PostgreSQL metadata.

## Contents

- **[Getting Started](getting-started.md)** — deploy git-shark with Docker Compose,
  from zero to a running instance behind TLS with OIDC login and SSH git access.

## Where else to look

- [`../README.md`](../README.md) — feature overview and architecture notes.
- Application configuration lives in `src/main/resources/application.properties`.
  Every production knob is overridable via an environment variable (listed in
  [Getting Started → Configuration reference](getting-started.md#configuration-reference)).
