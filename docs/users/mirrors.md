# Push mirrors

A push mirror replicates one of your repositories to an external remote (GitHub, GitLab,
another git-shark, any git server) **automatically after every push**. The mirror is an
exact replica: all branches and tags, including deletions (`git push --mirror` semantics).

Only the **repository owner** can see and manage a repository's mirrors.

## Adding a mirror

Open your repository's **Settings** page (the ⚙ Settings tab in the left sidebar, owner
only). You'll find a **Push mirrors** panel above the danger zone. Enter the remote URL
and pick how git-shark should authenticate:

### HTTPS remote (`https://…`)

1. Create the empty target repository at the remote host.
2. Get credentials that may push to it — for GitHub/GitLab that's your username plus a
   personal access token with write/`repo` scope.
3. Enter URL, select **HTTPS**, fill in username and password/token, and add the mirror.

The credentials are stored encrypted on the server and are **never shown again** — if a
token rotates, delete the mirror and create it again.

### SSH remote (`ssh://git@…` or `git@host:path`)

1. Enter the URL, select **SSH**, and add the mirror. git-shark generates a dedicated
   **Ed25519 deploy keypair** for this mirror; the private key never leaves the server.
2. The mirror row now shows the **public key** (`ssh-ed25519 …`, with a copy button).
   Register it at the remote with **write access** — on GitHub/GitLab as a *deploy key*
   with "allow write access" enabled.
3. The first successful contact pins the remote's host key; later syncs require the same
   host key (protection against server-swap attacks). If the remote legitimately changes
   its host key, delete and re-create the mirror.

## When does it sync?

- After **every push** to the repository (HTTP or SSH), asynchronously — your push never
  waits for, and never fails because of, the mirror.
- Several pushes in quick succession may be batched into a single sync; the remote always
  ends up at the current state.
- **Push now** triggers a sync manually (picked up by the background worker within a few
  seconds).

## Status and troubleshooting

Each mirror row shows the last successful sync, the last attempt, and — if the last
attempt failed — the error message. Failed syncs are retried automatically with growing
delays (1 min, 2 min, 4 min, … capped at 1 h). After the retry budget is exhausted the
mirror stops retrying and shows the error; the **next push** (or *Push now*) starts a
fresh sync.

Common failures:

| Symptom | Likely cause |
|---|---|
| `not authorized` / `authentication` errors | Wrong or expired token (HTTPS), or the deploy key isn't registered with write access (SSH) |
| Host key mismatch | The remote's SSH host key changed since it was pinned — delete and re-create the mirror if the change is legitimate |
| URL rejected when adding | Only `https://` and `ssh://` (or `git@host:path`) targets are allowed, and never this instance itself |

Deleting a mirror also deletes its stored credentials or keypair.
