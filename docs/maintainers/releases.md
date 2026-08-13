# Releases architecture

Releases are a thin database annotation over git tags, plus an archive writer.
The tag stays the source of truth for *what* was released; the `releases` row only
adds *how it is presented* (title, notes, pre-release flag) and remembers which
commit the tag resolved to when it was published.

## Component map

| Concern | Type | Notes |
|---|---|---|
| Entity | `de.workaround.model.Release` | `repository`, `author`, `tagName`, `title`, `body`, `commitId`, `prerelease`, `createdAt`; `Repo` carries the newest-first list, the stable-only list (for "Latest"), tag lookup and the nav count |
| Domain service | `git.ReleaseService` | Publish/edit/delete with `AccessPolicy.canWrite`; cuts a missing tag from the submitted target; blank title falls back to the tag name |
| Tag writes | `git.GitTagService` | `resolveCommit` (peels annotated tags), `exists`, `createTag` — annotated tag written in-core with `TagBuilder` + `RefUpdate`, no working tree |
| Archives | `git.GitArchiveService` | `Format.ZIP`/`TAR_GZ`; streams a ref's tree into an `OutputStream` |
| Web UI | `web.ReleaseResource` + `templates/ReleaseResource/*` | List, detail (Markdown notes via the shared `Markdown` renderer), new/edit forms |
| Archive endpoint | `web.RepositoryResource#archive` | `GET …/archive/{ref}.zip|.tar.gz`, visibility-guarded, `StreamingOutput` |
| REST | `api.ReleaseApiResource` + `ApiModels.ReleaseView`/`NewRelease`/`ReleaseEdit` | Gitea-shaped release contract, addressed by tag |
| Navigation | `web.RepoNav#releaseCount` (built in `RepoNavService`) | Sidebar entry between Tags and Issues |

## Data flow

**Publish.** `ReleaseResource` (or `ReleaseApiResource`) → `ReleaseService.create`
→ authorize → reject a blank tag name or a tag that already has a release → if the
tag exists, resolve it to a commit; otherwise `GitTagService.createTag` cuts an
annotated tag on the target and returns the tagged commit → persist the row →
`303` to `…/releases/tag/{tag}`.

**Read.** The list page reads `ReleaseService.list` plus `findLatest` (newest
release with `prerelease = false`) to place the **Latest** badge. The detail page
renders `body` through the shared XSS-safe `Markdown` renderer and links the two
archive URLs and the commit.

**Download.** `RepositoryResource#archive` picks the format from the URL suffix,
resolves the ref (404 if unknown), and hands a `StreamingOutput` to
`GitArchiveService`, which walks the commit's tree recursively and copies each
blob straight from the object database.

## Decisions

- **The tag is the key, not a surrogate number.** Issues and merge requests carry
  per-repo numbers; a release is *about* a tag, and Gitea's API addresses releases
  by tag too (`/releases/tags/{tag}`). A `unique (repository_id, tag_name)`
  constraint enforces one release per tag, and the UI routes are
  `…/releases/tag/{tag}` with a greedy match so slash-bearing tags work.
- **Deleting a release keeps the tag.** Releases are presentation metadata;
  deleting one must never rewrite history or break clones that fetched the tag.
  Deleting a *tag* is deliberately not offered anywhere in the UI.
- **Tag creation is in-core.** Cutting the tag while publishing (Gitea's
  `target_commitish`) means the common "tag and release in one step" flow needs no
  local clone. It reuses the same bare-repo, no-working-tree approach
  `GitMergeService` established for merges, with `RefUpdate` expecting a
  zero-id old value so a concurrent creation loses rather than overwrites. The
  ref write happens inside the publishing transaction, so a DB failure afterwards
  can leave an orphan tag — harmless, because publishing again simply adopts the
  existing tag instead of cutting a new one.
- **`commitId` is stored, the rest is read live.** The tag could later be deleted
  or moved by a force push; the release page must still be able to name the exact
  revision that was published. Everything else (tree, archives) is read live from
  git and never duplicated.
- **Archives use the JDK only.** `org.eclipse.jgit.archive` would pull in
  commons-compress purely for tar; a ~60-line ustar header writer plus
  `GZIPOutputStream` and `ZipOutputStream` keeps the native image's dependency
  surface unchanged. Long paths use the ustar `prefix` field; a path that fits
  neither field fails loudly instead of being silently truncated.
- **Blobs are streamed, not buffered.** `ObjectLoader.copyTo` writes into the
  response, so archive memory does not scale with repository size — the same
  reason the raw-blob endpoint streams.
- **No drafts.** Gitea's `draft` is reported as a constant `false`. A draft is a
  second visibility rule over the same row, and nothing needs it yet.

## What works today

- Publish a release for an existing tag, or cut an annotated tag from a branch,
  tag or commit while publishing.
- Markdown release notes (XSS-safe), pre-release flag, "Latest" badge that skips
  pre-releases.
- Edit title/notes/pre-release; delete the release while keeping the tag.
- Source archives (`.zip`, `.tar.gz`) for any ref, visibility-guarded and
  streamed, nested under a `<repo>-<ref>/` prefix.
- Sidebar entry with a release count on every repository page.
- Gitea-shaped REST: list, create, `latest`, get/PATCH/DELETE by tag, with
  `zipball_url`/`tarball_url` pointing at the archive endpoint.

## What still needs to be implemented

- **Release assets** — uploaded files per release (`/releases/{id}/assets` in
  Gitea), with a storage root, a size cap and a download endpoint. The archive
  links are the only downloads today.
- **Draft releases**, and with them a `draft` flag that is more than a constant.
- **MCP tools** for releases — the MCP surface mirrors the REST API elsewhere and
  currently has no release tools.
- **Federation** — a release publishes no ActivityPub activity, so followers of a
  repository learn about pushes but not about releases.
- **Auto-notes** — no "generate release notes from commits since the last tag".
- **Dashboard/notification integration** — releases contribute no
  `NotificationSource` items.
