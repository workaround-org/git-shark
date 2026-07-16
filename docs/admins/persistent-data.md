# Persistent data: what your deployment must store

git-shark keeps state in **one database and four filesystem locations**. Every one of
them must live on a persistent volume — anything written to the container's own
filesystem is lost when the container is recreated (every `docker compose up` after an
image update, config change, or host reboot).

Use this page as the checklist when setting up volumes, writing backup jobs, or
migrating a deployment to a new host.

## The five stores

| Store | Configured by | Default (in container) | Contents | If you lose it |
|---|---|---|---|---|
| PostgreSQL | `QUARKUS_DATASOURCE_*` | external service | Users, repository records, issues, merge requests, comments, SSH public keys, access-token hashes, push-mirror and federation state, CI runner registration-token and runner records (hashed secrets). Also each avatar's and repository image's content type and update timestamp — but **not** the image bytes. | Everything except the raw git objects and images. Total loss. |
| Repositories | `GITSHARK_STORAGE_ROOT` | `data/repositories` | The bare git repositories (all commits, branches, tags). | All hosted code. The DB rows survive but point at nothing. |
| Avatars | `GITSHARK_AVATAR_ROOT` | `data/avatars` | Uploaded profile pictures, one file per user, named by user UUID. | Profile pictures render as broken images (the DB still says the user has one, but `GET /users/{username}/avatar` returns 404). Users must re-upload. |
| Repository images | `GITSHARK_REPO_IMAGE_ROOT` | `data/repo-images` | Uploaded per-repository images, one file per repository, named by repository UUID. | Repository images render as broken images (the DB still says the repo has one, but `GET /repos/{owner}/{name}/image` returns 404); repos fall back to the owner's avatar once the DB row is also cleared. Owners must re-upload. |
| SSH host key | `GITSHARK_SSH_HOST_KEY` | `data/ssh/host-key` | The server's SSH host key, generated on first boot. | A new key is generated; every git client sees a host-key-changed warning and refuses to connect until `known_hosts` is fixed. |

If you front git-shark with Caddy as in the [Getting Started](getting-started.md) guide,
also persist Caddy's `/data` volume (`caddy-data`) — it holds the TLS certificates and
Let's Encrypt account.

In the reference Compose file all of these are named volumes: `db-data`, `repos`,
`avatars`, `repo-images`, and `ssh`. The defaults above are *relative* paths — inside a container they
resolve to a directory that vanishes with the container, so production deployments must
set the `GITSHARK_*` variables to absolute paths on mounted volumes, exactly as the
reference Compose file does.

## Checking a running deployment

```bash
docker compose exec app sh -c 'ls /data/repositories /data/avatars /data/repo-images /data/ssh'
docker inspect --format '{{range .Mounts}}{{.Destination}} <- {{.Source}}{{println}}{{end}}' \
  "$(docker compose ps -q app)"
```

Every path from the table must appear as a mount backed by a named volume (or a host
path) — not by the container's writable layer.

## Upgrading a deployment created before profile pictures

Deployments set up from a Getting Started guide older than the avatar feature have no
`avatars` volume, so uploaded profile pictures land in the container layer and disappear
on the next `docker compose up`. The symptom: avatars work until the app container is
recreated, then render as broken images.

Fix by aligning with the current reference Compose file:

1. Add the environment variable to the `app` service:

   ```yaml
         GITSHARK_AVATAR_ROOT: /data/avatars
   ```

2. Add the volume mount to the `app` service and the named volume to the top-level
   `volumes:` block:

   ```yaml
       volumes:
         - avatars:/data/avatars
   ```

3. Recreate the app: `docker compose up -d app`.

Pictures uploaded before the fix are gone (they lived in the discarded container
layer); affected users re-upload under *Settings → Profile*, or remove the picture
there to fall back to the initials badge.

## Backups

Back up all five stores together and consistently — a DB dump that references git
objects, avatar, or repository-image files from a different point in time is only crash-consistent. See
[Getting Started → Operations](getting-started.md#operations) for the commands.
