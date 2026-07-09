# Operating push mirrors

Push mirrors let repository owners replicate their repositories to external remotes on
every push. This page covers what an operator must configure and know: the secret key,
outbound network behavior, the sync queue, and the tables involved.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `GITSHARK_SECRET_KEY` | — | Symmetric key encrypting mirror secrets at rest (AES-256-GCM, key derived via SHA-256). **Required for mirrors**: without it, creating a mirror fails with a clear error (fail closed — secrets are never stored in plaintext). |
| `GITSHARK_MIRROR_MAX_ATTEMPTS` | `8` | Retry budget per sync before it is dead-lettered |
| `GITSHARK_MIRROR_ALLOW_INSECURE` | `false` | **Dev/local only.** Permits `http://` targets and loopback/private target hosts. Never enable in production. |

Pick a long random value for `GITSHARK_SECRET_KEY` (e.g. `openssl rand -base64 32`) and
keep it stable: changing it makes existing mirror secrets undecryptable — affected
mirrors will fail with a decryption error and must be deleted and re-created.

No new inbound endpoints need proxy rules; mirror management happens on the existing web
UI paths (`POST /repos/{owner}/{name}/mirrors[/{id}/delete|/{id}/push]`, owner-only).

## Outbound network requirements

Mirroring opens **outbound** connections from the app container to user-supplied hosts:

- `https://` targets on port 443 (or an explicit port),
- `ssh://` targets on port 22 (or an explicit port).

Built-in SSRF protection: only `https` and `ssh` URL schemes are accepted, the
instance's own host (from `GITSHARK_FEDERATION_BASE_URL`, when set) is rejected as a
target (loop protection), and target hosts that resolve to loopback/private/link-local
addresses are rejected — unless `GITSHARK_MIRROR_ALLOW_INSECURE` is set for local
testing. If your egress is firewalled, allow outbound 443/22 to the hosts your users
mirror to.

SSH host keys are handled *accept-new*: the key seen on the first successful sync is
pinned on the mirror record and enforced afterwards.

## The sync queue

Same operational model as the federation delivery queue:

- A push enqueues one sync per enabled mirror of the repository. At most **one pending
  sync per mirror** exists — rapid pushes coalesce (the sync always pushes the *current*
  state, so this is lossless).
- A scheduled worker drains due syncs **every 10 s**. Failures retry with exponential
  backoff (1 min, 2 min, 4 min, … capped at 1 h).
- After `GITSHARK_MIRROR_MAX_ATTEMPTS` failed attempts a sync is dead-lettered
  (`FAILED`) and retries stop. The next push to the repository (or the owner's
  *Push now*) enqueues a fresh sync.
- Mirror errors never affect the incoming git push — the client's push succeeds
  regardless of mirror state. Dead-letters are logged at WARN.

## Tables

| Table | Contents |
|---|---|
| `push_mirror` | One row per mirror: target URL, auth type, username, `encrypted_secret` (AES-GCM-encrypted token or SSH private key, `enc1:`-prefixed), SSH public deploy key, pinned host key, enabled flag, last attempt/success/error |
| `mirror_sync` | The queue: mirror reference, state (`PENDING`/`SYNCED`/`FAILED`), attempts, `next_attempt_at` |

Useful checks:

```sql
-- syncs currently failing or dead-lettered
select m.remote_url, s.state, s.attempts, s.last_error
from mirror_sync s join push_mirror m on m.id = s.mirror_id
where s.state = 'FAILED' or s.attempts > 0;

-- mirrors whose last attempt failed
select remote_url, last_attempt_at, last_error from push_mirror where last_error is not null;
```

Deleting a mirror (UI) removes its row and, via `on delete cascade`, its queue rows —
including the stored credentials/keypair. Deleting a repository cascades over its
mirrors the same way.
