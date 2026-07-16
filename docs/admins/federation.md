# Federation: deployment guide

How to enable and operate ForgeFed/ActivityPub federation on a git-shark
instance. For what federation offers end users, see the
[user guide](../users/federation.md); for a general deployment walkthrough, see
[Getting Started](getting-started.md). For architecture and implementation
decisions, see the [maintainer notes](../maintainers/forgefed.md).

Federation is **off by default** and fails closed: nothing is emitted or
accepted until it is explicitly enabled *and* correctly configured.

---

## Configuration

All settings come from environment variables:

| Variable | Default | Meaning |
|---|---|---|
| `GITSHARK_FEDERATION_ENABLED` | `false` | Master switch |
| `GITSHARK_FEDERATION_BASE_URL` | — | Public HTTPS origin of this instance, e.g. `https://shark.example.com`. Actor IDs derive from it |
| `GITSHARK_FEDERATION_PEER_ALLOWLIST` | — (empty) | Comma-separated peer **hosts**, e.g. `shark.other.org,forge.example`. Empty denies every remote peer |
| `GITSHARK_FEDERATION_MAX_ATTEMPTS` | `8` | Delivery attempts before an outbound activity is dead-lettered |
| `GITSHARK_FEDERATION_USER_RESYNC_INTERVAL` | `5m` | How often followed remote users are re-scanned for newly created public repositories (add-only) |
| `GITSHARK_FEDERATION_DEV_ALLOW_INSECURE` | `false` | **Dev only.** Permits `http://` and loopback/private targets. Never in production |

Minimal production setup:

```bash
GITSHARK_FEDERATION_ENABLED=true
GITSHARK_FEDERATION_BASE_URL=https://shark.example.com
GITSHARK_FEDERATION_PEER_ALLOWLIST=shark.other.org
```

> **`base-url` is permanent.** Actor IDs (`https://<base-url>/ap/...`) are
> published to other servers and cannot change without breaking every existing
> follow relationship. Set a real, stable, non-loopback HTTPS origin before
> enabling. git-shark refuses to operate federation (`operational = false`) while
> the base URL is unset, loopback, or otherwise unusable — the switch alone is
> not enough.

### The peer allowlist

Federation is mutual and allowlist-bound in **both** directions:

- **Inbound**: an activity is only accepted if its HTTP Signature's key belongs
  to an actor on an allowlisted host. Everything else is `401`.
- **Outbound**: actor fetches, WebFinger lookups, and deliveries only go to
  allowlisted hosts.

Matching is by exact, case-insensitive host name. Both instances must allowlist
each other, or follows stay Pending forever.

---

## What gets exposed

With federation operational, these endpoints are public (no login):

| Endpoint | Purpose |
|---|---|
| `GET /.well-known/webfinger?resource=acct:…` | Actor discovery (`user@host`, `owner/name@host`; the host part may be bare or `host:port`) |
| `GET /ap/repos/{owner}/{name}` | ForgeFed `Repository` actor (public repos only) |
| `GET /ap/users/{username}` | ActivityPub `Person` actor |
| `GET /ap/instance` | Instance `Application` actor |
| `GET …/outbox`, `…/followers` | Activity and follower collections |
| `GET /ap/users/{username}/repositories` | A user's public repository actors (discovery) |
| `POST …/inbox` | Signed inbound activities (repo, user, and instance inboxes) |

Private repositories are never exposed. Inbound posts are verified (HTTP
Signature, body digest, date skew), deduplicated by activity id, then dispatched.

---

## Reverse proxy requirements

Federation signs and verifies the `Host` header and the raw request path.
The proxy in front of git-shark must therefore:

- **Preserve the original `Host` header** (`proxy_set_header Host $host;` in
  nginx; Caddy does this by default). A rewritten host breaks signature
  verification on every inbound activity.
- Pass `/ap/*` and `/.well-known/webfinger` through **without** auth,
  path rewriting, or body buffering surprises.
- Terminate TLS — peers will only talk `https` to you.

The existing `quarkus.http.proxy.*` settings (already on in the default config)
make git-shark trust `X-Forwarded-*` so actor URLs and signature reconstruction
use the external origin.

---

## Keys and signing

- Every local actor (each public repository, each user, plus the instance) gets
  an **RSA-2048 keypair**, generated on first use and stored in the
  `federation_keys` table. There is no key configuration and currently no
  rotation mechanism; protect your database accordingly.
- Outbound activities are signed with draft-cavage HTTP Signatures
  (`SHA256withRSA`), key id `<actor-id>#main-key`.
- Outbound follows are signed as the **user's `Person` actor**; `Accept` and
  `Push` fan-out are signed as the repository actor.

---

## Delivery queue and retries

Outbound activities go through a persisted queue (`federation_delivery` table),
drained every 10 seconds:

- Failures retry with exponential backoff: 1m, 2m, 4m, … capped at 1h.
- After `GITSHARK_FEDERATION_MAX_ATTEMPTS` (default 8) the row is dead-lettered
  as `FAILED` with the last error preserved — it will not retry again.
- States: `PENDING` → `DELIVERED` or `FAILED`.

Monitoring queries worth having:

```sql
-- stuck or failing deliveries
select target_inbox, attempts, state, last_error, next_attempt_at
from federation_delivery
where state <> 'DELIVERED'
order by created_at desc;

-- follow relationships still waiting for the remote's Accept
select remote_actor_id, state, created_at from remote_follows where state = 'PENDING';
```

---

## Data stored for federation

| Table | Contents |
|---|---|
| `federation_keys` | Local actor keypairs (public + private PEM) |
| `remote_actors` | Cache of fetched remote actors (inbox, public key; 6h TTL) |
| `repository_followers` | Remote actors following local repositories |
| `remote_follows` | Local users' follows of remote repositories (`PENDING`/`ACCEPTED`); `via_user_actor_id` tags follows fanned out from a followed user |
| `remote_user_follows` | Local users' follows of remote users (each expands to a `remote_follows` row per public repo) |
| `received_pushes` | `Push` activities received from followed repositories (feed) |
| `federation_outbox` / `federation_inbox` | Published activities / inbound dedup log |
| `federation_delivery` | Outbound delivery queue |

---

## SSRF protection

All outbound federation traffic (actor fetches, WebFinger, deliveries) passes a
URL guard: HTTPS only, host must be allowlisted, and the resolved address must
not be loopback, link-local, private, or multicast. `dev-allow-insecure` relaxes
the scheme and address checks **but never the allowlist**.

---

## Local two-host trial (dev)

To try federation on one machine, run two dev instances that allowlist each
other:

```bash
# terminal A
GITSHARK_FEDERATION_ENABLED=true \
GITSHARK_FEDERATION_BASE_URL=http://localhost:8080 \
GITSHARK_FEDERATION_PEER_ALLOWLIST=localhost \
GITSHARK_FEDERATION_DEV_ALLOW_INSECURE=true \
./mvnw quarkus:dev

# terminal B (second checkout or worktree)
GITSHARK_FEDERATION_ENABLED=true \
GITSHARK_FEDERATION_BASE_URL=http://localhost:8081 \
GITSHARK_FEDERATION_PEER_ALLOWLIST=localhost \
GITSHARK_FEDERATION_DEV_ALLOW_INSECURE=true \
GITSHARK_SSH_PORT=2223 \
./mvnw quarkus:dev -Dquarkus.http.port=8081 -Ddebug=false \
  -Dquarkus.datasource.devservices.shared=false
```

On B, log in and follow `alice/demo@localhost:8080` (dev seed data) from the
Following page. The follow flips to Accepted within ~20s; pushing to the demo
repo on A makes the push appear under "Recent pushes" on B.

---

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Follow stays `PENDING` | Peer not allowlisted (either side), remote down, or delivery dead-lettered — check `federation_delivery.last_error` |
| Inbound activities all `401` | Signer host not on your allowlist, or the reverse proxy rewrites the `Host` header |
| `Could not resolve handle` | Remote host not allowlisted locally, WebFinger unreachable, or repo not public |
| Actor documents `404` | Federation not operational: switch off, or `base-url` unset/loopback |
| Deliveries fail with `Only https is allowed` | Peer published an `http://` inbox/actor URL and you are not in dev-insecure mode |
