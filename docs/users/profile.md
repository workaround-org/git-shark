# Profile settings: user guide

Your profile settings live at `/settings/profile` (link: **Profile** in the
header navigation). From there you can change your handle and display name,
and upload a profile picture.

---

## Username and display name

- **Username** — your URL-safe handle (`^[a-z0-9][a-z0-9-]{0,38}$`). It's what
  appears in every repo, SSH, and federation URL, and it's chosen once during
  [onboarding](../admins/getting-started.md#step-7--bring-it-up); you can
  change it later here.
- **Display name** — a freeform name shown alongside your username in the UI.
  Pre-filled from your OIDC `name` claim on first login, editable anytime.

---

## Profile picture

The **Profile picture** section lets you upload an avatar:

- **Allowed formats**: PNG, JPEG, GIF, WebP.
- **Max size**: 2 MB.
- Pick a file and press **Upload**. Uploading again replaces the existing
  picture.
- If you already have a picture, a **Remove picture** button appears —
  removing it falls back to an initials badge (the first letter of your
  username) everywhere your avatar was shown.

The server checks both the declared file type and the file's actual content
before accepting it, so renaming a file to fake its type doesn't work — you'll
get an error and nothing is saved.

### Where your avatar shows up

Once uploaded, your picture appears everywhere your account is rendered as a
user:

- The header navigation, next to the **Profile** link.
- Repository lists on the home page and `/explore`.
- The owner in a repository's left sidebar.
- As the author of issues, merge requests, and merge-request review comments.

On repository surfaces (the lists and the sidebar), a repository that has its own
[repository image](repository-image.md) shows that image instead of your avatar;
your avatar is the fallback when the repository has none.

**Not covered:** git commit authors (shown on a repository's overview page and
in the commit log) come from the commit's git identity, not your account, so
they always show an initials badge regardless of your uploaded picture.
Remote federated users (shown on the [Following](federation.md) page) are also
not covered — they're identified by their remote handle, not a local account.

Your uploaded picture is served publicly at `/users/<your-username>/avatar` so
it can be embedded on public pages without requiring the viewer to be logged
in.
