# Getting Started: deploying git-shark with Docker Compose

This guide takes you from an empty host to a running git-shark instance:

1. A PostgreSQL database (metadata store).
2. The git-shark application container (web UI + smart-HTTP git + embedded SSH).
3. A TLS-terminating reverse proxy in front (git-shark requires HTTPS).
4. An external OIDC provider for login (kanidm, Keycloak, or any OpenID Connect IdP).

By the end you can browse the web UI over HTTPS, log in with OIDC, and clone/push
over both `https://` and `ssh://`.

> **git-shark has no built-in password login.** Authentication to the web UI is
> delegated entirely to an OIDC provider. You must have one reachable (or stand one
> up) before the app is usable. Git transport authenticates separately — personal
> access tokens over HTTP Basic, SSH public keys over SSH.

---

## Prerequisites

- **Docker Engine 24+** and the **Docker Compose v2** plugin (`docker compose`, not the
  legacy `docker-compose`).
- A **DNS name** pointing at the host (e.g. `gitshark.example.com`). OIDC redirect URIs
  and — if you enable federation — permanent actor IDs are derived from it.
- An **OIDC provider** with an authorization-code client for git-shark. PKCE is required.
- Ports **80/443** (reverse proxy) and the **SSH git** port (host **22** by default, mapped
  to container `2222`) reachable from your clients.

---

## Step 1 — Get the application image

Use the prebuilt image published to GitHub Container Registry — nothing to build:

```bash
docker pull ghcr.io/workaround-org/git-shark:latest
```

Pin a specific release instead of `latest` for reproducible deploys (e.g.
`ghcr.io/workaround-org/git-shark:1.0.0`).

The JVM image is published as a multi-arch manifest for **linux/amd64** and
**linux/arm64** — `docker pull` picks the right variant automatically, so it runs
natively on x86 servers and ARM hosts (Raspberry Pi 4/5, AWS Graviton, Apple
Silicon) alike.

A **native image** (GraalVM native executable, no JVM) is also published with a
`-native` tag suffix:

```bash
docker pull ghcr.io/workaround-org/git-shark:latest-native
```

It starts faster and uses less memory than the JVM image, but is currently built
for **linux/amd64 only** and runs as UID `1001` (the JVM image uses `185` —
adjust `securityContext`/volume ownership accordingly if you switch). Ports and
environment variables are identical to the JVM image.

The image listens on **8080** (HTTP) and, once configured, **2222** (SSH). It runs as
UID `185` and reads all production settings from environment variables.

> **Building it yourself instead.** git-shark ships a JVM Dockerfile, so from the repo
> root you can build a local image and point the Compose file's `image:` at it:
>
> ```bash
> ./mvnw package                                              # produces target/quarkus-app/
> docker build -f src/main/docker/Dockerfile.jvm -t git-shark:local .
> ```
>
> For a smaller, faster-starting image build the native variant (`-Dnative` with
> `Dockerfile.native-micro`).

---

## Step 2 — Register the OIDC client

git-shark uses the OIDC **authorization code flow** with **PKCE**. Create a confidential
client at your IdP and note three things: the **issuer/discovery URL**, the **client ID**,
and the **client secret**. Set the redirect URI to the fixed callback path
`https://gitshark.example.com/login` (git-shark pins the code-flow callback to `/login`
via `quarkus.oidc.authentication.redirect-path`, so IdPs with strict `redirect_uri`
matching — kanidm does this — only need that one URI registered). After the token
exchange git-shark returns the user to the page they were originally on.

### kanidm example

```bash
kanidm system oauth2 create git-shark "Git Shark" https://gitshark.example.com
kanidm system oauth2 add-redirect-url git-shark https://gitshark.example.com/login
kanidm group create gitshark_users
kanidm group add-members gitshark_users <your-user>
kanidm system oauth2 update-scope-map git-shark gitshark_users openid profile email
kanidm system oauth2 show-basic-secret git-shark      # -> client secret
```

The auth-server URL for kanidm is `https://<kanidm-host>/oauth2/openid/git-shark`.

### Keycloak / other IdPs

Create a confidential client with:
- Standard flow (authorization code) enabled, PKCE `S256` required.
- Redirect URI `https://gitshark.example.com/login`.
- Scopes `openid profile email`.

The auth-server URL is the realm issuer, e.g.
`https://keycloak.example.com/realms/<realm>`.

### Session lifetime and silent refresh

The IdP's ID tokens can be short-lived (kanidm issues ~15 min tokens by design); git-shark
does **not** log the user out when one expires. Instead it refreshes the tokens inline with
the refresh token stored in the encrypted session cookie
(`quarkus.oidc.token.refresh-expired=true`, proactively 60 s before expiry) and keeps the
session cookie usable for up to 12 h past ID-token expiry
(`quarkus.oidc.authentication.session-age-extension=PT12H`). This requires the IdP to
actually issue a refresh token to the client — kanidm does for the code flow. kanidm's
refresh-token lifetime is currently hard-coded to 16 h, so that is the ceiling for a fully
silent session; past it the code flow simply runs again (still invisible while the IdP's own
SSO session is alive, otherwise the user logs in once and lands back on the page they were on).

---

## Step 3 — Generate the encryption secrets

Two secrets encrypt the PKCE state cookie and the post-login session cookie. Each must
be **at least 32 characters** (Quarkus minimum). Generate them once and keep them stable
— rotating them invalidates in-flight logins and existing sessions.

```bash
openssl rand -hex 16      # 32 hex chars — run twice, for the two secrets below
```

---

## Step 4 — Write the `.env` file

Compose reads these values. Put the file next to `docker-compose.yml`, keep it out of
version control (it holds secrets).

```dotenv
# --- Public origin ---
APP_DOMAIN=gitshark.example.com

# --- PostgreSQL ---
POSTGRES_DB=gitshark
POSTGRES_USER=gitshark
POSTGRES_PASSWORD=change-me-strong-db-password

# --- OIDC (from Step 2) ---
OIDC_AUTH_SERVER_URL=https://idm.example.com/oauth2/openid/git-shark
OIDC_CLIENT_ID=git-shark
OIDC_CLIENT_SECRET=the-basic-secret-from-your-idp

# --- OIDC cookie encryption (from Step 3, >= 32 chars each) ---
OIDC_STATE_SECRET=paste-first-openssl-rand-output
OIDC_TOKEN_STATE_SECRET=paste-second-openssl-rand-output
```

---

## Step 5 — The Compose file

```yaml
name: git-shark

services:
  db:
    image: postgres:17
    restart: unless-stopped
    environment:
      POSTGRES_DB: ${POSTGRES_DB}
      POSTGRES_USER: ${POSTGRES_USER}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD}
    volumes:
      - db-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${POSTGRES_USER} -d ${POSTGRES_DB}"]
      interval: 10s
      timeout: 5s
      retries: 5

  app:
    image: ghcr.io/workaround-org/git-shark:latest    # or a pinned tag; see Step 1
    restart: unless-stopped
    depends_on:
      db:
        condition: service_healthy
    environment:
      # --- Datasource ---
      QUARKUS_DATASOURCE_JDBC_URL: jdbc:postgresql://db:5432/${POSTGRES_DB}
      QUARKUS_DATASOURCE_USERNAME: ${POSTGRES_USER}
      QUARKUS_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
      # --- OIDC ---
      QUARKUS_OIDC_AUTH_SERVER_URL: ${OIDC_AUTH_SERVER_URL}
      QUARKUS_OIDC_CLIENT_ID: ${OIDC_CLIENT_ID}
      QUARKUS_OIDC_CREDENTIALS_SECRET: ${OIDC_CLIENT_SECRET}
      QUARKUS_OIDC_AUTHENTICATION_STATE_SECRET: ${OIDC_STATE_SECRET}
      QUARKUS_OIDC_TOKEN_STATE_ENCRYPTION_SECRET: ${OIDC_TOKEN_STATE_SECRET}
      # --- Storage & SSH ---
      GITSHARK_STORAGE_ROOT: /data/repositories
      GITSHARK_AVATAR_ROOT: /data/avatars
      GITSHARK_REPO_IMAGE_ROOT: /data/repo-images
      GITSHARK_SSH_HOST_KEY: /data/ssh/host-key
      GITSHARK_SSH_PORT: "2222"          # bind port INSIDE the container (>1024 → no root needed)
      GITSHARK_SSH_EXTERNAL_PORT: "22"   # port shown in clone URLs; MUST match the published host port below
    ports:
      - "22:2222"                   # publish SSH git on the standard port 22 (host 22 → container 2222)
    volumes:
      - repos:/data/repositories    # bare git repositories
      - avatars:/data/avatars       # user profile pictures
      - repo-images:/data/repo-images   # per-repository images
      - ssh:/data/ssh               # persistent SSH host key
    healthcheck:
      test: ["CMD-SHELL", "exec 3<>/dev/tcp/127.0.0.1/8080 && echo ok >&3"]
      interval: 15s
      timeout: 5s
      retries: 10
      start_period: 40s

volumes:
  db-data:
  repos:
  avatars:
  repo-images:
  ssh:
```

Notes:

- **Separate volumes for `/data/repositories`, `/data/avatars`, `/data/repo-images`,
  and `/data/ssh`** so Docker creates each mount point with the right ownership — no
  init container or `mkdir` needed. This works because the image ships those
  directories pre-owned by the runtime user, and Docker copies that ownership into a
  freshly created named volume. Volumes first created with an image from before this
  fix are root-owned and stay that way — see
  [Persistent data](persistent-data.md#fixing-root-owned-data-volumes) for the
  one-time repair. The SSH host key is generated on first boot and
  persists across restarts (so client `known_hosts` entries stay valid). All four mounts
  (plus the database) are mandatory for a stateful deployment — see
  [Persistent data](persistent-data.md) for what each holds and what breaks without it.
- **HTTP port 8080 is not published** — it's reached through the reverse proxy on the
  Compose network (Step 6). Only SSH is exposed directly (host **22** → container **2222**).
- **Two SSH port knobs, and they are independent.** `GITSHARK_SSH_PORT` is the port the
  embedded server *binds inside the container* — keep it above 1024 (default `2222`) so the
  process never needs root. `GITSHARK_SSH_EXTERNAL_PORT` is *display only*: it is the port
  git-shark writes into the clone/push URLs shown in the UI (default `22`, and `22` is
  omitted from the printed URL for a clean `ssh://git@host/...`). It changes **no** runtime
  behaviour — the server still binds `GITSHARK_SSH_PORT`. **You are responsible for making
  the two agree with your port publishing:** the external port must equal the host port you
  publish. Above we bind `2222`, publish it on host `22`, and advertise `22` — consistent.
  If the host already runs its own sshd on `22`, publish the container on `2222` instead
  (`"2222:2222"`) **and** set `GITSHARK_SSH_EXTERNAL_PORT: "2222"` so the advertised URL
  matches what clients can actually reach.
- **Single app replica.** git-shark keeps git state on a `ReadWriteOnce`-style filesystem
  volume; do not scale `app` beyond one instance.
- Flyway migrates the schema automatically at startup (`migrate-at-start=true`), so the
  database needs no manual initialization beyond an empty database + owner.

---

## Step 6 — TLS reverse proxy (required)

git-shark always builds **HTTPS** OIDC redirect URIs and trusts `X-Forwarded-*` headers
(`force-redirect-https-scheme=true`, `proxy-address-forwarding=true`). It is designed to
run behind a TLS-terminating proxy — plain HTTP will break the login redirect.

Add a Caddy service to the Compose file — it fetches and renews a Let's Encrypt
certificate automatically:

```yaml
  proxy:
    image: caddy:2
    restart: unless-stopped
    depends_on:
      - app
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy-data:/data
      - caddy-config:/config
```

Add `caddy-data:` and `caddy-config:` to the top-level `volumes:` block, then create a
`Caddyfile` next to the Compose file:

```caddyfile
gitshark.example.com {
    reverse_proxy app:8080
}
```

Caddy forwards `X-Forwarded-Proto`/`-For`/`-Host` by default, which is exactly what
git-shark's OIDC redirect construction needs. Using Traefik or nginx instead is fine —
just terminate TLS and forward those headers.

---

## Step 7 — Bring it up

```bash
docker compose up -d
docker compose logs -f app        # watch for "Listening on: http://0.0.0.0:8080"
```

Then open `https://gitshark.example.com/`, click **Log in**, and complete the OIDC flow.
On first login you're redirected to `/onboarding` to pick a URL-safe handle
(`^[a-z0-9][a-z0-9-]{0,38}$`) — this handle, not the IdP username, appears in all repo,
SSH, and federation URLs.

---

## Step 8 — Verify git access

**HTTP** (anonymous read on public repos; push/private read use a personal access token
as the HTTP Basic password — create one under *Access tokens* in the UI):

```bash
git clone https://gitshark.example.com/git/<owner>/<repo>.git
```

**SSH** (public-key only; add your key under *SSH keys* in the UI):

```bash
git clone git@gitshark.example.com:<owner>/<repo>.git
```

> On the default port `GITSHARK_SSH_EXTERNAL_PORT=22` the UI shows the short scp-like form
> above (`git@host:owner/repo.git`, exactly like GitHub) — matching the `"22:2222"` publish.
> The scp shorthand **cannot carry a port**, so if you publish SSH on a non-standard host
> port (e.g. `"2222:2222"`), set `GITSHARK_SSH_EXTERNAL_PORT` to that port and the UI falls
> back to the explicit form `ssh://git@gitshark.example.com:2222/...`. The external port is
> display-only; it must equal the reachable host port or the copy-paste clone command fails.

---

## Configuration reference

Every value below is an environment variable on the `app` service. Defaults come from
`src/main/resources/application.properties`.

| Variable | Required | Default | Purpose |
|---|---|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | ✅ | — | PostgreSQL JDBC URL |
| `QUARKUS_DATASOURCE_USERNAME` | ✅ | — | DB user |
| `QUARKUS_DATASOURCE_PASSWORD` | ✅ | — | DB password |
| `QUARKUS_OIDC_AUTH_SERVER_URL` | ✅ | — | OIDC issuer / discovery URL |
| `QUARKUS_OIDC_CLIENT_ID` | ✅ | — | OIDC client ID |
| `QUARKUS_OIDC_CREDENTIALS_SECRET` | ✅ | — | OIDC client secret |
| `QUARKUS_OIDC_AUTHENTICATION_STATE_SECRET` | ✅ | — | Encrypts PKCE state cookie (≥ 32 chars) |
| `QUARKUS_OIDC_TOKEN_STATE_ENCRYPTION_SECRET` | ✅ | — | Encrypts session/token cookie (≥ 32 chars) |
| `GITSHARK_STORAGE_ROOT` | — | `data/repositories` | On-disk bare-repo root |
| `GITSHARK_AVATAR_ROOT` | — | `data/avatars` | On-disk profile-picture (avatar) storage root |
| `GITSHARK_REPO_IMAGE_ROOT` | — | `data/repo-images` | On-disk per-repository image storage root |
| `GITSHARK_SSH_HOST_KEY` | — | `data/ssh/host-key` | Persistent SSH host key path |
| `GITSHARK_SSH_PORT` | — | `2222` | Port the embedded SSH server **binds inside the container**; keep >1024 so it needs no root |
| `GITSHARK_SSH_EXTERNAL_PORT` | — | `22` | Port advertised in clone/push URLs (display only, no runtime effect). Must match the published host port; `22` is omitted from the printed URL |
| `GITSHARK_SECRET_KEY` | — | — | Encrypts push-mirror secrets at rest; required to create mirrors (see [Push mirrors](mirrors.md)) |
| `GITSHARK_MIRROR_MAX_ATTEMPTS` | — | `8` | Mirror-sync retry cap before dead-letter |
| `GITSHARK_MIRROR_ALLOW_INSECURE` | — | `false` | Dev only: allow http/loopback mirror targets |
| `GITSHARK_MIRROR_DRAIN_INTERVAL` | — | `10s` | How often the async mirror-sync drain worker runs |
| `GITSHARK_FEDERATION_ENABLED` | — | `false` | Turn on ForgeFed/ActivityPub |
| `GITSHARK_FEDERATION_BASE_URL` | — | — | Public HTTPS origin; permanent actor-ID base |
| `GITSHARK_FEDERATION_PEER_ALLOWLIST` | — | — | Comma-separated peer hosts (empty denies all) |
| `GITSHARK_FEDERATION_MAX_ATTEMPTS` | — | `8` | Outbound delivery retry cap |
| `GITSHARK_FEDERATION_USER_RESYNC_INTERVAL` | — | `5m` | Re-scan followed users for new public repos |
| `GITSHARK_FEDERATION_DEV_ALLOW_INSECURE` | — | `false` | Dev only: allow http/loopback peers |
| `GITSHARK_ADMIN_HANDLES` | — | — | Comma-separated handles allowed into `/admin/*` (CI runner management); empty means no admins (see [CI runners](ci-runners.md)) |
| `GITSHARK_GITEA_API_VERSION` | — | `1.13.0` | Version string reported by `GET /api/v1/version`. The `/api/v1` surface is Gitea-compatible; Gitea clients (Renovate, `tea`) gate features on this. Kept below `1.14.0` so they only call implemented endpoints — raise it as reviewer/label/status support lands |

### Optional: push mirrors

Repository owners can mirror their repositories to external remotes on every push. The
only prerequisite is a stable secret key for encrypting the mirror credentials at rest:

```yaml
      GITSHARK_SECRET_KEY: <openssl rand -base64 32>
```

Without it, creating a mirror fails (secrets are never stored unencrypted). Operational
details — outbound network requirements, queue behavior, tables — in
[Push mirrors](mirrors.md).

### Optional: federation (ForgeFed)

Off by default. Enabling it publishes **permanent** actor IDs derived from
`GITSHARK_FEDERATION_BASE_URL`, so set a real, stable, non-loopback HTTPS origin before
turning it on — git-shark refuses to emit actor documents otherwise.

```yaml
      GITSHARK_FEDERATION_ENABLED: "true"
      GITSHARK_FEDERATION_BASE_URL: https://gitshark.example.com
      GITSHARK_FEDERATION_PEER_ALLOWLIST: peer-a.example,peer-b.example
```

Inbound activities need a valid HTTP Signature from an allowlisted peer; outbound
fetches are HTTPS-only, allowlist-bound, and SSRF-guarded. Never set
`GITSHARK_FEDERATION_DEV_ALLOW_INSECURE=true` in production.

---

## Operations

**Backups** — four things hold state (full inventory, including what breaks when each
is lost, in [Persistent data](persistent-data.md)):
- The `db-data` volume (metadata: users, repo records, issues, MRs, comments;
  also each avatar's and repository image's content type and update timestamp —
  the bytes are not here).
- The `repos` volume (the actual git objects).
- The `avatars` volume (uploaded profile-picture bytes, one file per user).
- The `repo-images` volume (uploaded per-repository image bytes, one file per repo).

Back all four up together and consistently. A logical DB dump:

```bash
docker compose exec db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > gitshark-db.sql
```

Snapshot the `repos`, `avatars`, and `repo-images` volumes with your host's
volume/snapshot tooling while the app is quiesced (or accept crash-consistent
snapshots — bare repos, avatar, and repository-image files all tolerate them well).

**Upgrades** — pull the new image and recreate the app:

```bash
docker compose pull app
docker compose up -d app
```

Flyway applies any new migrations on startup. Because the app uses the `Recreate`
pattern (one writer, filesystem state), a brief downtime during redeploy is expected.

**Logs & health**:

```bash
docker compose ps
docker compose logs -f app
```

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Login redirects to `http://…` or loops | Proxy not forwarding `X-Forwarded-Proto`, or you hit the app over plain HTTP. Front it with TLS (Step 6). |
| Boot fails on OIDC discovery | `QUARKUS_OIDC_AUTH_SERVER_URL` wrong/unreachable, or IdP demands HTTPS the app can't reach. |
| IdP rejects login with a `redirect_uri` error | The client's registered redirect URI doesn't match the fixed callback `https://<host>/login` (Step 2). Deployments set up before the silent-refresh change registered `https://<host>/` — add/replace it with `/login`. |
| App exits complaining about secret length | `*_STATE_SECRET` shorter than 32 chars. Regenerate with `openssl rand -hex 16`. |
| SSH host key changed after redeploy | The `ssh` volume wasn't persisted — confirm it's a named volume, not a throwaway mount. If the volume **is** there but stays empty and the logs show `Failed (AccessDeniedException) to write EC key`, the mount points are root-owned (volumes created with a pre-fix image) — see the row below. |
| Repository creation or image/avatar upload fails; app logs show `Permission denied` or `AccessDeniedException` under `/data` | The `/data` volumes were first created by an image that didn't ship those directories, so their mount points are root-owned and the app user (UID 185, or 1001 for the native image) can't write. One-time fix: `docker compose exec --user 0 app chown -R 185:0 /data && docker compose restart app` (use `1001:0` for the native image). Details in [Persistent data](persistent-data.md#fixing-root-owned-data-volumes). |
| Profile pictures disappear after redeploy / render as broken images | The `avatars` volume wasn't mounted, so uploads landed in the container layer. Add the volume and `GITSHARK_AVATAR_ROOT` as in Step 5 — retrofit steps in [Persistent data](persistent-data.md#upgrading-a-deployment-created-before-profile-pictures). |
| `git push` over HTTP rejected | Use a personal access token (from *Access tokens*) as the Basic-auth password (or username — either works, like a GitHub PAT), not your OIDC password. |
| Schema validation error at start | DB not empty / migrated by a different tool. git-shark's Flyway owns the schema; start from an empty database. |
