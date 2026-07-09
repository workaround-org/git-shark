# Avatars: implementation notes

Maintainer-facing notes on the profile-picture (avatar) feature: where the
bytes live, how uploads are validated, the single render point, and what's
deliberately out of scope. For the user-facing behavior see the
[user guide](../users/profile.md); for deployment/config see
[Getting Started](../admins/getting-started.md).

---

## Storage: filesystem, not the database

Avatar bytes live on the local filesystem under `gitshark.storage.avatars`
(env `GITSHARK_AVATAR_ROOT`, default `data/avatars`), one file per user named
by the user's UUID (`AvatarService.avatarPath`). This mirrors the existing
convention for bare git repositories under `gitshark.storage.root` — large
binary blobs go on disk, not in PostgreSQL.

The `users` table only stores metadata, added in
`db/migration/V10__user_avatar.sql`:

- `avatar_content_type` (nullable) — the validated MIME type, needed to serve
  the file with the right `Content-Type`. `NULL` means the user has no
  avatar (`User.hasAvatar()`).
- `avatar_updated_at` (nullable) — last upload timestamp, used only to
  cache-bust the `<img>` URL (`?v=<epoch millis>`) so browsers pick up a
  replaced picture immediately.

`AvatarService` is the only component that touches the filesystem; upload
(`store`) and removal (`remove`) are `@Transactional` so the DB row and the
file move together as far as the request is concerned (a crash between the
file write and the commit can still leave them inconsistent — no two-phase
commit is attempted, consistent with how bare-repo writes are handled).

## Validation

All validation happens in `ImageValidation.validate` — a shared helper used by
both `AvatarService` (called from `SettingsResource.uploadAvatar`) and the
per-repository image feature (see [Repository images](repo-images.md)), so the
rules stay identical in one place:

- **Size cap**: 2 MB (`ImageValidation.MAX_BYTES`).
- **Type allowlist**: PNG, JPEG, GIF, WebP (`image/png`, `image/jpeg`,
  `image/gif`, `image/webp`).
- **Magic-byte check**: the declared content type must match the file's
  actual leading bytes (`ImageValidation.ALLOWED`, e.g. PNG's `\x89PNG\r\n\x1a\n`
  signature). This rejects a file that lies about its type — declaring
  `image/png` but uploading something else fails validation rather than being
  stored and served back with a wrong/dangerous content type.

Validation failures throw `InvalidImageException`, caught in
`SettingsResource` and re-rendered as a form error on `/settings/profile`.

## Rendering: one Qute tag, one render point

`templates/tags/avatar.html` is the single place that decides how to render a
user:

```html
{#if user.hasAvatar}<img class="avatar" src="/users/{user.username}/avatar?v={user.avatarUpdatedAt.toEpochMilli}" alt="{user.username}">{#else}<span class="av av-fallback">{user.username.charAt(0)}</span>{/if}
```

Every template that shows a local user invokes it as `{#avatar user=... /}`
(header nav, repo lists, repo sidebar, issue/MR/comment authors — see
`templates/layout.html`, `templates/HomeResource/*.html`,
`templates/RepositoryResource/sidebar.html`,
`templates/IssueResource/issue.html`,
`templates/MergeRequestResource/mergeRequest.html`). Keeping the fallback
logic in one tag means there's no place in the UI that can show a stale or
inconsistent avatar state — a page either has the tag or it doesn't render a
user avatar at all.

## Serving endpoint

`GET /users/{username}/avatar` (`AvatarResource`) is deliberately **public** —
no authentication — unlike the upload/delete endpoints under `/settings/*`.
This is what lets avatars embed on public repository pages for anonymous
visitors. It returns `404` when the user has no avatar (`hasAvatar()` false)
or the file is missing from disk, and otherwise streams the bytes with the
stored `avatar_content_type` and
`Cache-Control: public, max-age=31536000, immutable`. Immutable caching is
safe because every rendered avatar URL carries the `?v=<epoch millis>`
cache-buster — replacing the picture changes the URL, so browsers never
serve a stale cached response for the new URL. This includes the settings
preview on `/settings/profile`, which is versioned like the avatar tag.

Upload and delete are authenticated, under `/settings/profile/avatar`
(`POST`, multipart, field `avatar`) and `/settings/profile/avatar/delete`
(`POST`) respectively, both in `SettingsResource`.

## What's covered / not covered

**Covered** — anywhere a local `User` is rendered: header nav, home/explore
repository lists, repository sidebar owner, issue authors, merge-request
authors, and merge-request review-comment authors.

**Not covered, on purpose:**

- **Git commit authors.** The repository overview's "latest commit" and the
  commit log render an initials badge built directly from the commit's git
  identity string (`RepositoryResource/overview.html`), not from a `User`
  lookup. A commit's author name/email is free-form data from the git object,
  not necessarily tied to (or even matching) a local account, so there's no
  reliable way to resolve it to an uploaded avatar without guessing.
- **Remote federation actors.** Entries on the Following page represent
  remote ForgeFed actors (`RemoteActor`), which are handles/URLs from another
  instance, not local `User` rows — the avatar tag and storage only apply to
  accounts on this instance.
