# Forks

Forking lets a user copy any repository they can read into their own namespace.
It needs **no configuration** — no `GITSHARK_*` properties and no background
jobs.

## Schema

Migration `V17__repository_fork.sql` adds one column to `repositories`:

| Column | Type | Notes |
|---|---|---|
| `parent_repo_id` | `uuid` NULL | FK → `repositories(id)`, `ON DELETE SET NULL`, indexed (`idx_repositories_parent`) |

`ON DELETE SET NULL` means deleting a source repository leaves its forks
standing as independent repositories (their `parent_repo_id` is cleared) rather
than cascading the deletion. No new tables.

## Endpoints

| Method & path | Auth | Effect |
|---|---|---|
| `POST /api/v1/repos/{owner}/{name}/fork` | Bearer token (required) | Fork into the caller's namespace; `201` with the new `RepositoryView`, `409` if the caller already has a repository of that name, `404` if the source is not readable, `401` without a token |
| `POST /repos/{owner}/{name}/fork` | Session (required) | Same, from the UI; `303` to the fork (or to the caller's existing repository of that name), `403` for anonymous callers |

The `RepositoryView` payload gains two nullable fields, `parentOwner` and
`parentName`. They are populated only when the repository is a fork **and the
caller can read the parent**, so a source turned private after being forked is
never disclosed through its forks. Listing endpoints omit them entirely.

## Behavior

- The fork copies the source's name, visibility, and description, and clones the
  bare repository on disk with `git clone --bare` semantics (all branches, plus
  reachable tags) into the forking user's storage directory.
- Visibility is enforced up front: the caller must be able to **read** the
  source, so a private repository is never exposed through a fork. A private
  source produces a private fork.
- Storage grows by roughly the size of the source repository per fork — clones
  are independent copies, not shared object stores. Account for this in disk
  provisioning if forking is heavily used.

## Storage

Forks live under the same `gitshark.storage.root` layout as any other
repository (`<owner-id>/<repo-id>.git`); see
[Persistent data](persistent-data.md). Nothing fork-specific needs a separate
volume.
