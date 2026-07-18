# Viewing a commit's changes

The **Commits** tab of a repository lists every commit on a branch. Click a
commit's short id or its message to open the **commit detail page**, which shows
exactly what that commit changed.

## What the page shows

- The commit's full id, author, and date, and its message as the heading.
- A **Changes** section: every file the commit touched, file by file, with
  added lines in green and removed lines in red, plus per-file and total
  *files changed / +additions / −deletions* counts.

The diff is the change the commit introduced on its own — that is, the difference
between the commit and its parent. For the very first commit in a repository (a
*root* commit, which has no parent) every file shows as an addition, because there
was nothing before it.

The diff is always computed live from git; nothing is stored in the database.

## Getting there

- From the repository sidebar, open **Commits**, then click any row.
- Or go straight to `…/commit/<id>`, where `<id>` is the commit's full or
  abbreviated hash (e.g. `…/commit/1a2b3c4`).
