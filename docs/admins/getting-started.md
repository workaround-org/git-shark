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
- Ports **80/443** (reverse proxy) and **2222** (SSH git) reachable from your clients.

---

## Step 1 — Get the application image

Use the prebuilt image published to GitHub Container Registry — nothing to build:

```bash
docker pull ghcr.io/workaround-org/git-shark:latest
```

Pin a specific release instead of `latest` for reproducible deploys (e.g.
`ghcr.io/workaround-org/git-shark:1.0.0`).

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
and the **client secret**. Set the redirect URI to your public origin with a trailing
slash: `https://gitshark.example.com/`.

### kanidm example

```bash
kanidm system oauth2 create git-shark "Git Shark" https://gitshark.example.com
kanidm system oauth2 add-redirect-url git-shark https://gitshark.example.com/
kanidm group create gitshark_users
kanidm group add-members gitshark_users <your-user>
kanidm system oauth2 update-scope-map git-shark gitshark_users openid profile email
kanidm system oauth2 show-basic-secret git-shark      # -> client secret
```

The auth-server URL for kanidm is `https://<kanidm-host>/oauth2/openid/git-shark`.

### Keycloak / other IdPs

Create a confidential client with:
- Standard flow (authorization code) enabled, PKCE `S256` required.
- Redirect URI `https://gitshark.example.com/`.
- Scopes `openid profile email`.

The auth-server URL is the realm issuer, e.g.
`https://keycloak.example.com/realms/<realm>`.

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
      GITSHARK_SSH_HOST_KEY: /data/ssh/host-key
      GITSHARK_SSH_PORT: "2222"
    ports:
      - "2222:2222"                 # SSH git access, published directly
    volumes:
      - repos:/data/repositories    # bare git repositories
      - avatars:/data/avatars       # user profile pictures
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
  ssh:
```

Notes:

- **Separate volumes for `/data/repositories`, `/data/avatars`, and `/data/ssh`** so
  Docker creates each mount point with the right ownership — no init container or
  `mkdir` needed. The SSH host key is generated on first boot and persists across
  restarts (so client `known_hosts` entries stay valid).
- **HTTP port 8080 is not published** — it's reached through the reverse proxy on the
  Compose network (Step 6). Only SSH (2222) is exposed directly.
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
git clone ssh://git@gitshark.example.com:2222/<owner>/<repo>.git
```

> Want bare `git@gitshark.example.com` without the `:2222`? Publish the container's 2222
> on host port 22 (`"22:2222"`) — but only if the host's own sshd isn't already using 22.

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
| `GITSHARK_SSH_HOST_KEY` | — | `data/ssh/host-key` | Persistent SSH host key path |
| `GITSHARK_SSH_PORT` | — | `2222` | Embedded SSH server port |
| `GITSHARK_FEDERATION_ENABLED` | — | `false` | Turn on ForgeFed/ActivityPub |
| `GITSHARK_FEDERATION_BASE_URL` | — | — | Public HTTPS origin; permanent actor-ID base |
| `GITSHARK_FEDERATION_PEER_ALLOWLIST` | — | — | Comma-separated peer hosts (empty denies all) |
| `GITSHARK_FEDERATION_MAX_ATTEMPTS` | — | `8` | Outbound delivery retry cap |
| `GITSHARK_FEDERATION_DEV_ALLOW_INSECURE` | — | `false` | Dev only: allow http/loopback peers |

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

**Backups** — three things hold state:
- The `db-data` volume (metadata: users, repo records, issues, MRs, comments;
  also each avatar's content type and update timestamp — the bytes are not
  here).
- The `repos` volume (the actual git objects).
- The `avatars` volume (uploaded profile-picture bytes, one file per user).

Back all three up together and consistently. A logical DB dump:

```bash
docker compose exec db pg_dump -U "$POSTGRES_USER" "$POSTGRES_DB" > gitshark-db.sql
```

Snapshot the `repos` and `avatars` volumes with your host's volume/snapshot tooling
while the app is quiesced (or accept crash-consistent snapshots — bare repos and
avatar files both tolerate them well).

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
| App exits complaining about secret length | `*_STATE_SECRET` shorter than 32 chars. Regenerate with `openssl rand -hex 16`. |
| SSH host key changed after redeploy | The `ssh` volume wasn't persisted — confirm it's a named volume, not a throwaway mount. |
| `git push` over HTTP rejected | Use a personal access token (from *Access tokens*) as the Basic-auth password, not your OIDC password. |
| Schema validation error at start | DB not empty / migrated by a different tool. git-shark's Flyway owns the schema; start from an empty database. |
