# Running Renovate against git-shark

git-shark's `/api/v1` is [Gitea-compatible](../maintainers/gitea-api.md), so
[Renovate](https://docs.renovatebot.com/) drives it with its stock `gitea`
platform driver — no git-shark-specific plugin. Renovate opens, updates, and
merges dependency-update pull requests on repositories hosted here.

## Prerequisites

- A personal access token for the account Renovate acts as (create one under
  **Settings → Access tokens**). The account needs write access to the target
  repositories (owner or collaborator).
- Renovate reaches both the API host and the git host — for a typical
  deployment that is the same public origin (`https://gitshark.example.com`),
  with the API under `/api/v1` and git under `/git/<owner>/<repo>.git`.

## Configuration

Point Renovate's `gitea` platform at the `/api/v1` endpoint. Minimal
self-hosted config:

```json
{
  "platform": "gitea",
  "endpoint": "https://gitshark.example.com/api/v1",
  "token": "gs_your_access_token",
  "repositories": ["alice/deptest"],
  "onboarding": false,
  "dependencyDashboard": false,
  "requireConfig": "optional"
}
```

Or entirely by environment variable:

```
RENOVATE_PLATFORM=gitea
RENOVATE_ENDPOINT=https://gitshark.example.com/api/v1
RENOVATE_TOKEN=gs_your_access_token
RENOVATE_REPOSITORIES=alice/deptest
RENOVATE_ONBOARDING=false
RENOVATE_DEPENDENCY_DASHBOARD=false
LOG_LEVEL=debug renovate
```

Notes and current limitations:

- **Do not set `RENOVATE_GIT_URL=endpoint`.** git-shark serves git under
  `/git/<owner>/<repo>.git`, not at the API path; leave `git-url` unset so
  Renovate clones from the repository's `clone_url` (which carries the correct
  path). Renovate injects the token as the Basic username, which git-shark
  accepts.
- **`dependencyDashboard: false`** — the dashboard needs issue open/closed
  mapping and issue-comment endpoints that are not implemented yet.
- **`onboarding: false`** — pin the target repositories in `repositories`
  rather than relying on an onboarding PR / autodiscovery.
- **Labels and commit statuses are stubs**: labels are always empty and the
  combined commit status is reported all-clear, so Renovate treats branches as
  passing. There is no real CI gating yet.
- Release-notes retrieval logs a warning without a `github.com` token; it does
  not block PR creation.

## Scheduled runs via GitHub Actions

This repository ships `.github/workflows/renovate.yml`, which runs Renovate on
a weekly schedule (Mondays 06:00 UTC) and on manual dispatch. The workflow runs
on GitHub (via the mirror) and reaches back to `https://gitshark.de/api/v1` to
open PRs on `workaround/Gitshark`.

To enable it, add repository secrets on the GitHub mirror:

- **`RENOVATE_TOKEN`** (required) — a git-shark personal access token for an
  account with write access to the target repository.
- **`RENOVATE_GITHUB_COM_TOKEN`** (optional) — a `github.com` read-only token so
  Renovate can fetch changelogs/release notes and avoid public rate limits.

Trigger a first run manually from the **Actions → Renovate → Run workflow**
button rather than waiting for the schedule. Tune what gets updated via the
repository's root `renovate.json`; widen coverage by adding repositories to
`RENOVATE_REPOSITORIES` in the workflow (keep `RENOVATE_AUTODISCOVER=false` to
stay explicit).

## Verifying

With `LOG_LEVEL=debug`, a successful run clones the repo, extracts the
dependency manifest, pushes a `renovate/<dep>-<range>` branch, and opens a pull
request. Confirm via the UI (the repository's merge requests) or the API:

```
curl -H "Authorization: token gs_your_access_token" \
  https://gitshark.example.com/api/v1/repos/alice/deptest/pulls?state=open
```
