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
| Auth (REST) | `ApiTokenAuthFilter` | Accepts both `Authorization: Bearer <PAT>` and the Gitea-style `Authorization: token <PAT>`; same personal access tokens as git-over-HTTP |
| Auth (git) | `GitBasicAuthFilter` | Basic auth for git-over-HTTP: the PAT is accepted as the **password or the username** (Renovate clones with the token in the username, empty password — like a GitHub PAT) |
| DTOs | `ApiModels` | All response records are Gitea-shaped; snake_case fields via `@JsonProperty`. **Shared with the MCP tools** — reshaping the REST body reshapes MCP tool output too (accepted; see decisions) |
| Surrogate ids | `GiteaIds` | Folds a `UUID` PK into a stable non-negative `long` for Gitea's int64 `id` |
| Version probe | `VersionApiResource` | `GET /api/v1/version`; string from `gitshark.gitea-api.version` |
| Repositories | `RepositoryApiResource` | Gitea repository object incl. `owner`, `full_name`, `default_branch`, `clone_url`, `html_url`, `permissions`, merge flags |
| Pulls | `PullApiResource` | Merge requests as Gitea pull requests (list/create/get/PATCH/merge + line-review comments); domain stays `MergeRequest*` |
| Labels | `LabelApiResource` | Always `[]` — no label model yet |
| Statuses | `CommitStatusApiResource` | All-clear combined status + echoing `POST /statuses/{sha}`; no status store yet |
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
  the one merge strategy the merge service implements. `default_merge_style` is
  reported as `merge` and is **load-bearing, not cosmetic**: Renovate picks a
  merge method by running `default_merge_style` first through a `.find`, and its
  `isAllowed` *throws* on an unrecognized style — so omitting the field (leaving
  it undefined on the wire) makes Renovate block the whole repository with
  "unknown merge style" before it ever checks `allow_merge_commits`.
- **`mergeable` is a placeholder** = "the pull is open", not a real conflict
  check. Computing true mergeability needs a live trial-merge per pull; Renovate
  only needs a hint, and the actual `POST {number}/merge` still rejects a real
  conflict, so the cheap approximation is safe.
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
- `pulls` resource (`/api/v1/repos/{owner}/{name}/pulls`) — merge requests
  projected as Gitea pull requests (`number`/index, `id`, `title`, `body`,
  `state` open/closed + `merged`, `head`/`base` refs, `mergeable`, email-free
  `user`/`assignee`, empty `labels`). Create takes `{title, body, head, base}`;
  `PATCH {number}` edits `title`/`body` and closes (`state:"closed"`) or reopens
  (`state:"open"`); `POST {number}/merge` merges (strategy body ignored — only
  merge commits). List supports `?state=open|closed|all` and pagination (page
  size capped at 50 so Renovate's paging terminates). The line-review comments
  are git-shark's own feature, kept under `pulls/{number}/comments`.
- `GET labels` → `[]` (no label model yet; Renovate skips labels when empty).
- Commit-status stubs: `GET commits/{ref}/status` reports an all-clear combined
  status, `GET commits/{ref}/statuses` is empty, and `POST statuses/{sha}` echoes
  the posted status without persisting (no status store yet). Enough for Renovate
  to treat a branch as passing and proceed; `ref` is matched greedily for slashes.

## What still needs to be implemented

- Find-by-branch `GET pulls/{base}/{head}` — deliberately skipped: Renovate finds
  a branch's pull by listing and filtering client-side, and the two-segment route
  would collide with `pulls/{index}` / `pulls/{index}/comments`.
- A real commit-status store wired to the CI runners (replace the stubs above).
- `GET /repos/{owner}/{name}/contents/{path}` (Renovate mostly clones, so low
  priority).
- Issue open/closed mapping + issue-comment REST endpoints (dependency dashboard);
  deferred — run Renovate with `dependencyDashboard: false`.

## Validation

A real Renovate `LOG_LEVEL=debug` run (`platform: gitea`, `endpoint:
http://localhost:8080/api/v1`, `git-url` unset so it clones via `clone_url`)
against a seeded repo with an outdated npm dependency **opened dependency PRs
end to end**, and merging one via `POST /pulls/{number}/merge` advanced `main`.
Two field-fidelity bugs surfaced and were fixed as part of this:

- **`default_merge_style` must be present** (see decisions) — Renovate blocked
  the whole repo without it.
- **git auth must accept the token as the Basic username** — Renovate clones
  with the PAT in the username and an empty password.

The npm registry lookup for the dependency needs outbound network; Release-notes
retrieval warns without a `github.com` token but does not block PR creation.
