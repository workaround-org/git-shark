# Gitea-compatible REST API

git-shark's `/api/v1` is being migrated from a bespoke JSON shape to the
**Gitea REST contract**, so Gitea-ecosystem tooling — [Renovate](https://docs.renovatebot.com/)
first, then `tea`, `act_runner`, and assorted bots — can drive a git-shark
instance with its stock `gitea`/`forgejo` platform driver and no git-shark-specific
code. Tracked in issue #20.

## Why migrate rather than run a shim

The REST API has no external consumers yet, so reshaping it in place is a rename,
not a breaking change — the cheapest possible moment to do it. A parallel
Gitea shim next to the bespoke API would fight the existing routes for the same
paths (`GET /api/v1/repos/{owner}/{name}` wants one JSON body, not two) and leave
two schemas to maintain forever. One Gitea-native schema is less code and broader
compatibility.

The domain layer keeps its own vocabulary: internally these are **merge requests**
(`MergeRequest*`), and only the wire speaks Gitea's "pull request". The mapping is
confined to the DTO/resource layer in `de.workaround.api`.

## Component map

| Concern | Type | Notes |
|---|---|---|
| Auth | `ApiTokenAuthFilter` | Accepts both `Authorization: Bearer <PAT>` and the Gitea-style `Authorization: token <PAT>`; same personal access tokens as git-over-HTTP |
| DTOs | `ApiModels` | All response records are Gitea-shaped; snake_case fields via `@JsonProperty`. **Shared with the MCP tools** — reshaping the REST body reshapes MCP tool output too (accepted; see decisions) |
| Surrogate ids | `GiteaIds` | Folds a `UUID` PK into a stable non-negative `long` for Gitea's int64 `id` |
| Version probe | `VersionApiResource` | `GET /api/v1/version`; string from `gitshark.gitea-api.version` |
| Repositories | `RepositoryApiResource` | Gitea repository object incl. `owner`, `full_name`, `default_branch`, `clone_url`, `html_url`, `permissions`, merge flags |
| User | `UserApiResource` | Self identity in Gitea user shape |
| Search | `SearchApiResource` | git-shark-specific (not a Gitea endpoint); returns the email-free `PersonView` and a shallow repository projection |

## Key decisions

- **Reported version is deliberately low** (`gitshark.gitea-api.version`, default
  `1.13.0`). Gitea clients gate feature calls on it: `>= 1.14.0` makes Renovate
  call `requested_reviewers`, `>= 1.24.0` unlocks `delete_branch_after_merge` —
  neither is implemented, so the version is kept below them until they are. Raise
  it as capabilities land.
- **Surrogate `id` is one-way.** `GiteaIds.of(uuid)` is a display value only;
  git-shark never looks an entity up by it — owner/name and per-repo `number`
  (Gitea's `index`) remain the real keys. The fold is lossy; do not reverse it.
- **MCP shares the DTOs.** MCP tools return `ApiModels` records directly, so they
  now emit Gitea-shaped JSON. Accepted (option (a) in the spike): the shape is
  richer, not worse, for AI-agent consumers. MCP has no request base URL, so the
  repository `clone_url`/`html_url` are null there while the REST resource fills
  them from the request's external base URI.
- **Fields git-shark has no feature for are hard-coded:** `archived` and `mirror`
  are always false (no archive feature; push-mirrors are outbound, not incoming
  mirrors), and the `allow_*` merge flags advertise merge commits only, matching
  the one merge strategy the merge service implements.
- **PII:** the self-scoped `UserView` carries `email`; the search `PersonView`
  omits it, because search is anonymous and would otherwise disclose every
  matched user's address.

## What works today

- `Authorization: token <PAT>` scheme alongside `Bearer`.
- `GET /api/v1/version` — Gitea version probe.
- `GET /api/v1/user` — self identity in Gitea user shape (`id`, `login`,
  `username`, `full_name`, `email`).
- `GET /api/v1/repos` and `GET /api/v1/repos/{owner}/{name}` — Gitea repository
  objects, including `default_branch` (live git read), `clone_url`/`html_url`
  (from the request base URL), `permissions`, `fork`/`parent`, and merge flags.
- `GET /api/v1/repos/{owner}/{name}/branches/{branch}` — branch object
  (`name`, `commit.id`, `protected`); the branch segment is matched greedily so
  slash-bearing names resolve, and only real branch refs count (tag/SHA → 404).

## What still needs to be implemented

- `pulls` resource: rename `merge-requests` → `pulls`, reshape to Gitea pull
  requests, add find-by-branch (`GET pulls/{base}/{head}`), `PATCH pulls/{index}`
  (title/body/state), keep create/get/list/merge.
- `GET labels` → `[]` stub and commit-status stubs (`POST /statuses/{sha}`,
  `GET /commits/{ref}/statuses`) so Renovate proceeds.
- `GET /repos/{owner}/{name}/contents/{path}` (Renovate mostly clones, so low
  priority).
- Issue open/closed mapping + issue-comment REST endpoints (dependency dashboard);
  deferred — run Renovate with `dependencyDashboard: false`.
- A real Renovate `LOG_LEVEL=debug` end-to-end run to validate JSON fidelity
  (field-name mismatches fail silently).
