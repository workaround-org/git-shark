# Comments and per-commit diffs — implementation notes

Two collaboration/inspection features that share the existing diff and comment
machinery rather than adding parallel copies of it.

## Issue and merge-request comments

General (non-line) discussion comments on issues and merge requests.

### Data model

- **Issues** — a dedicated `IssueComment` entity / `issue_comments` table
  (`V21__issue_comments.sql`): `id`, `issue_id` (FK, `ON DELETE CASCADE`),
  `author_id` (FK, `ON DELETE CASCADE`), `body`, `created_at`. Mirrors
  `MergeRequestComment` minus the diff-anchor columns.
- **Merge requests** — general comments **reuse** the existing
  `merge_request_comments` table. `V22__merge_request_discussion_comments.sql`
  drops the `NOT NULL` on `file_path`; a general comment has `file_path = null`
  and `old_line = new_line = -1`, while a per-line review comment keeps its path
  and line anchor. This avoids a second MR-comment table and lets one delete
  endpoint serve both kinds.

### Services

- `IssueCommentService` — `add` / `list` / `delete`. Any reader may comment;
  author, owner, or collaborator may delete (`AccessPolicy.canWrite`). Empty
  bodies are rejected with `InvalidIssueException` (→ 400).
- `MergeRequestCommentService.addGeneral` — sibling of the line-anchored `add`,
  but with no diff-anchor check: it sets `filePath = null` and both line numbers
  to `-1`.

### Web layer

- `IssueResource`: `POST {number}/comments` and
  `POST {number}/comments/{commentId}/delete`; the detail view passes the comment
  list plus `loggedIn` / `currentUserId` / `canModerate` to `issue.html`. The
  delete control renders when the viewer is the comment author or `canModerate`
  (`AccessPolicy.canWrite` — owner, collaborator or org member), matching what the
  service enforces; `owner` (admin) alone would hide it from collaborators.
- `MergeRequestResource`: `POST {number}/discussion` for general comments;
  deletion reuses the existing `{number}/comments/{commentId}/delete`. `detail`
  splits `commentService.list(mr)` into line comments (`filePath != null`) and
  discussion (`filePath == null`) — the line-matching stream now filters on the
  line-comment sublist, so a general comment's null `filePath` never reaches the
  `.equals(...)` match (which would NPE).

Rendering is shared markup: both `issue.html` and `mergeRequest.html` render the
thread with the same `comment` / `comment-head` / `comment-body` classes already
used by per-line comments, so no new CSS is required.

## Per-commit diff view

Reuses the merge-request diff core instead of introducing a new differ.

- `GitMergeService.commitDiff(barePath, commitId)` — resolves the commit and its
  first parent (or an `EmptyTreeIterator` for a root commit), scans the two trees
  with a `DiffFormatter`, and runs each `DiffEntry` through the same private
  `formatEntry` used by branch diffs, returning the existing `DiffView` record. A
  well-formed but unknown id (including the all-zero id) surfaces as
  `MissingObjectException` during `resolve` and is mapped to `Optional.empty()`
  (→ 404) rather than a 500.
- `GitBrowseService.commit(barePath, rev)` — single-commit metadata
  (`CommitInfo`) for the page header, with the same `MissingObjectException` →
  empty handling.
- `RepositoryResource`: `GET commit/{id}` renders `commit.html` (read-only diff,
  no comment affordances). Rows in `commits.html` link to it. The route prefix
  `commit/` does not collide with the existing `commits/{ref}` listing.

The diff is always computed live from git; nothing is duplicated into the
database, matching the merge-request diff decision.
