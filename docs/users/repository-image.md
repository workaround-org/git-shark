# Repository image: user guide

Give a repository its own picture instead of showing your profile picture next
to it. Only the repository's owner can set this, from the repository's
**Settings** page.

---

## Setting a repository image

1. Open the repository and click **Settings** in the left sidebar (only the
   owner sees this link), or go to `/repos/<owner>/<name>/settings`.
2. Under **Repository image**, pick a file and press **Upload**.

- **Allowed formats**: PNG, JPEG, GIF, WebP.
- **Max size**: 2 MB.
- Uploading again replaces the existing image.
- If the repository already has an image, a **Remove image** button appears.

The server checks both the declared file type and the file's actual content
before accepting it, so renaming a file to fake its type doesn't work — you'll
get an error and nothing is saved. (These are the same rules as your
[profile picture](profile.md).)

## Fallback: the owner's avatar

A repository with no custom image shows its **owner's profile picture** — the
same as before this feature existed. Uploading an image overrides that; removing
it falls back to the owner's avatar again. So a repository's look never changes
until someone deliberately sets an image.

## Where the repository image shows up

Once set, the image replaces the owner's avatar wherever the repository is
listed:

- The repository's left sidebar.
- Repository lists on the home page and `/explore`.
- Your dashboard (pinned and all repositories).

## Visibility

The image is served at `/repos/<owner>/<name>/image`. For a **private**
repository it is only visible to people who can already see the repository —
anyone else gets a "not found", so the image never reveals a private repo's
existence.
