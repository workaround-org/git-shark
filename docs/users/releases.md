# Releases

A **release** turns a git tag into something people can read and download: a
title, Markdown release notes, and source-code archives of exactly that tag.
Every repository has a **Releases** section in its left sidebar, next to Tags.

Releases annotate tags — they never change history. Deleting a release keeps the
tag it was published from.

## Who can do what

| Action | Who |
|---|---|
| Browse releases, read notes, download archives | Anyone who can read the repository |
| Publish, edit, delete a release | The repository owner, its collaborators, and organisation members |

A private repository's releases (and its archives) are invisible to everyone
without read access — the pages answer `404`, not `403`.

## Publishing a release

1. Open **Releases → New release**.
2. **Tag** — type the tag name, e.g. `v1.0.0`. The field suggests tags that
   already exist and have no release yet.
3. **Create the tag from** — pick a branch when the tag does not exist yet.
   git-shark then creates an annotated tag on that branch's current commit as
   part of publishing. When the tag already exists, this choice is ignored.
4. **Title** — optional; defaults to the tag name.
5. **Release notes** — optional Markdown (headings, lists, tables, links).
   Embedded raw HTML is escaped, so notes can never inject markup into the page.
6. **This is a pre-release** — tick for a release candidate or beta.

Publishing takes you straight to the new release page.

## The release page

- The **Latest** badge marks the newest release that is *not* a pre-release.
  Pre-releases are listed normally but never become "latest".
- **Source code (zip)** and **Source code (tar.gz)** download the repository tree
  at that tag. Both unpack into a single `<repo>-<tag>/` directory.
- The commit link opens the exact revision the tag pointed at when the release
  was published.
- Owners and collaborators get **Edit** and **Delete** buttons.

Editing changes the title, the notes and the pre-release flag. The tag and its
commit stay fixed — publish a new release to ship a new version.

## Downloading without the UI

The archive URLs work for any ref, not just released tags:

```
https://<host>/repos/<owner>/<repo>/archive/<ref>.zip
https://<host>/repos/<owner>/<repo>/archive/<ref>.tar.gz
```

`<ref>` may be a tag, a branch, or a commit id. For a private repository, the
usual login (browser session) is required.

## Limits today

- **No file attachments.** A release carries notes and the source archives; you
  cannot upload binaries or checksums yet.
- **No drafts.** A published release is immediately visible to everyone who can
  read the repository.
- Releases are not announced to federation followers.

Related: [Comments](comments.md) · [Repository visibility](repository-visibility.md)
