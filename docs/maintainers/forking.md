# Forking architecture

How a fork is created, stored, and represented, and the decisions behind it.

## Component map

- **`GitRepositoryService.fork(User actor, Repository source)`** — the single
  entry point. Validates read access, checks for a name collision in the
  actor's namespace, persists the new `Repository` row (with `parent` set to the
  source), then clones the bare repository on disk. Reused by every surface.
- **`Repository.parent`** (`@ManyToOne`, column `parent_repo_id`) — the fork
  link. `@ManyToOne` is EAGER by default, so `parent` loads with the repository
  and both the API projection and the Qute sidebar can render “forked from …”
  without a second query. `Repository.isFork()` is the null check.
- **`RepositoryApiResource.fork`** — `POST /api/v1/repos/{owner}/{name}/fork`,
  returns `201`/`409`/`404`/`401`.
- **`RepositoryTools.forkRepository`** — the MCP mirror of the REST endpoint.
- **`RepositoryResource.fork`** — the UI `POST …/fork`, redirecting to the fork.
- **`ApiModels.RepositoryView`** — carries `parentOwner`/`parentName`.

## Data flow

1. The surface resolves the source through the normal visibility gate
   (`requireReadable`), so an unreadable private source is a `404`/`403` before
   `fork()` runs.
2. `fork()` re-checks `accessPolicy.canRead(actor, source)` — defense in depth,
   independent of the caller — then guards against a duplicate name in the
   actor's namespace.
3. It persists the fork row inside the method's transaction.
4. It clones the source's bare repository into the fork's on-disk path with
   `Git.cloneRepository().setBare(true).setCloneAllBranches(true)` over a
   `file://` URI, copying HEAD, all branches, and reachable tags.

## Decisions

- **Clone, not filesystem copy.** A JGit bare clone reproduces the ref set and
  object database cleanly and skips source-specific junk (stale `config`
  remotes, hooks). The trade-off is that each fork is an independent object
  store — no shared/alternates dedup — which is acceptable at git-shark's target
  scale and keeps deletion trivial. If storage pressure ever matters, git
  alternates are the place to look.
- **`ON DELETE SET NULL`, not cascade.** Deleting a source must not delete other
  people's forks. A fork whose parent is gone simply loses its “forked from”
  link and becomes a standalone repository.
- **Visibility inherited, never widened.** The fork takes the source's
  visibility and the actor must already be able to read the source, so forking
  can never turn a private repository public or expose it to someone new.
- **Parent link gated per viewer, not just at fork time.** The "forked from"
  link and the API's `parentOwner`/`parentName` are shown only when the *current
  viewer* can read the parent (`accessPolicy.canRead(viewer, parent)`), computed
  in `RepoNavService` for the UI and via `canSeeParent(...)` in the REST/MCP
  resources. This matters because a source can be flipped to private *after*
  being forked; without the per-viewer check, a public fork would keep leaking
  the now-private parent's owner/name. `RepositoryView.of(repo)` hides the parent
  by default, so listings never disclose it — callers opt in with
  `of(repo, showParent)`.
- **Personal namespace only (v1).** Forks land under the acting user, never an
  organisation. Forking into an org is a future extension.
- **Fork button open to any logged-in reader**, and a repeat fork redirects to
  the caller's existing repository of that name rather than erroring — a
  minimal stand-in for a fork picker.

## What works today

- Fork any readable repository into your personal namespace (UI, REST, MCP).
- Full ref copy: HEAD, all branches, reachable tags.
- `parent`/`isFork` model, “forked from” link, and `parentOwner`/`parentName`
  in the API projection.
- Visibility enforcement (private sources never exposed; private fork of a
  private source), including the per-viewer "forked from" gate that keeps a
  source turned private after forking from leaking through its public forks.
- `ON DELETE SET NULL` so forks outlive their source.

## What still needs to be implemented

- **Cross-repo merge requests** — opening an MR from a fork branch against the
  parent repository. The `parent` link is in place; the MR subsystem does not
  yet target a different repository.
- **Fork syncing** — “update from upstream” to pull later parent changes into a
  fork.
- **Fork discovery** — a fork network/graph view and a “forks” count on the
  source.
- **Forking into an organisation** namespace.
- **Storage sharing** (git alternates) to avoid a full object copy per fork.
