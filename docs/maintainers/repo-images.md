# Repository images: implementation notes

Maintainer-facing notes on the per-repository image feature: where the bytes
live, how it reuses the avatar machinery, the render point with owner-avatar
fallback, and the visibility-guarded serving endpoint. For the user-facing
behavior see the [user guide](../users/repository-image.md); for
deployment/config see [Getting Started](../admins/getting-started.md). This
feature deliberately mirrors [avatars](avatars.md) — read that first.

---

## Storage: filesystem, not the database

Repository image bytes live on the local filesystem under
`gitshark.storage.repo-images` (env `GITSHARK_REPO_IMAGE_ROOT`, default
`data/repo-images`), one file per repository named by the repository's UUID
(`RepositoryImageService.imagePath`). Same convention as avatars and bare git
repos — large binary blobs go on disk, not in PostgreSQL.

The `repositories` table only stores metadata, added in
`db/migration/V13__repository_image.sql`:

- `image_content_type` (nullable) — the validated MIME type, needed to serve the
  file with the right `Content-Type`. `NULL` means the repository has no custom
  image (`Repository.hasImage()`) and falls back to its owner's avatar.
- `image_updated_at` (nullable) — last upload timestamp, used only to cache-bust
  the `<img>` URL (`?v=<epoch millis>`).

`RepositoryImageService` is the only component that touches the filesystem;
`store` and `remove` are `@Transactional`, same consistency caveat as
`AvatarService`. When a whole repository is deleted, `GitRepositoryService.delete`
calls `RepositoryImageService.deleteFileFor` so the image bytes are removed too —
otherwise they would be orphaned on disk under a UUID no row points at anymore.

## Validation: shared with avatars

Validation is **not** duplicated. `RepositoryImageService.store` calls the shared
`ImageValidation.validate` (2 MB cap, PNG/JPEG/GIF/WebP allowlist, magic-byte
check — WebP additionally verifies the `WEBP` form type at offset 8, since a bare
`RIFF` prefix is also WAV/AVI) — the exact same helper `AvatarService` uses.
Failures throw the shared `InvalidImageException`, caught in
`RepositoryResource.uploadImage` and re-rendered as a form error on the
repository settings page.

## Rendering: one Qute tag, owner-avatar fallback

`templates/tags/repoAvatar.html` is the single place that decides how to render a
repository, delegating to the user avatar tag when there is no custom image:

```html
{#if repo.hasImage}<img class="avatar" src="/repos/{repo.owner.username}/{repo.name}/image?v={repo.imageUpdatedAt.toEpochMilli}" alt="{repo.name}">{#else}{#avatar user=repo.owner /}{/if}
```

Every template that shows a repository invokes it as `{#repoAvatar repo=... /}`
instead of the previous `{#avatar user=repo.owner /}` (repository sidebar,
home/explore repo lists, dashboard). Because the fallback branch reuses the
avatar tag, repositories keep exactly their previous look until an image is
uploaded.

## Serving endpoint: visibility-guarded

`GET /repos/{owner}/{name}/image` (`RepositoryResource.image`) is **not** public,
unlike the user avatar endpoint. It goes through `requireReadable`, so a private
repository's image is `404` for anyone who can't read the repo — the image must
not leak repository existence or content. It returns `404` when the repo has no
image (`hasImage()` false) or the file is missing, otherwise streams the bytes
with the stored `image_content_type` and a `Cache-Control` header of
`max-age=31536000, immutable` — scoped `public` for public repositories but
`private` for private ones, so a shared cache (reverse proxy, CDN) never
stores a private repository's image and serves it to someone the visibility
guard would have rejected. Immutable caching is safe because rendered image
URLs carry the `?v=<epoch millis>` cache-buster.

Upload and delete are owner-only, guarded by `RepositoryResource.requireOwner`
(which returns `404` — not `403` — to non-owners, consistent with how private
repos are hidden): `POST /repos/{owner}/{name}/image` (multipart, field `image`)
and `POST /repos/{owner}/{name}/image/delete`. As defense-in-depth,
`RepositoryImageService.store`/`remove` also take the acting `User` and assert
`AccessPolicy.canWrite` themselves (mirroring `GitRepositoryService.delete` and
`RepositoryPinService`), so a future non-REST caller can't bypass the check. The upload UI lives on the
owner-only settings page `GET /repos/{owner}/{name}/settings`
(`templates/RepositoryResource/settings.html`), linked from the repo sidebar only
when `RepoNav.owner` is true.

## Decisions

- **Owner-avatar fallback, not an initials badge.** The request was to let a repo
  *override* the owner picture it already showed; keeping the owner avatar as the
  fallback means no repository's appearance changes until someone opts in.
- **Shared `ImageValidation`/`InvalidImageException`.** Extracted from
  `AvatarService` rather than copied so the size cap, type allowlist, and
  magic-byte rules can never drift between the two upload paths.
- **Dedicated settings page, not the overview.** Repository-owner actions get
  their own page (`/settings`) with room to grow, surfaced via an owner-only
  sidebar link.

## What's covered / not covered

**Covered** — anywhere a repository is rendered with an avatar: the repository
sidebar, the home/explore repository lists, and the dashboard (pinned + all
repositories).

**Not covered, on purpose:**

- **Federation actor documents.** A repository's ForgeFed `Repository` actor does
  not expose the custom image as an `icon`; this is a UI-only feature for now.
