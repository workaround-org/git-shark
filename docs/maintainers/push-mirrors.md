# Push mirrors — architecture notes

How the push-mirror subsystem (issue #11) is built, and why.

## Component map

| Piece | Where | Role |
|---|---|---|
| `PushMirror`, `MirrorSync` | `model/` | Mirror record (secret encrypted via `@Convert`) and queue row; tables `push_mirror`, `mirror_sync` (migration `V11__push_mirror.sql`) |
| `MirrorService` | `mirror/` | Owner-facing CRUD, enqueue-on-push, scheduled drain (`@Scheduled every 10s`), retry/backoff/dead-letter bookkeeping |
| `MirrorPusher` | `mirror/` | One JGit push attempt: ref-advertisement read, mirror update set, HTTPS credentials or per-mirror SSH session factory |
| `SecretCrypto`, `EncryptedStringConverter` | `mirror/` | AES-256-GCM at-rest encryption keyed from `gitshark.secret-key` (SHA-256-derived); converter resolves the bean lazily via ArC |
| `MirrorKeys` | `mirror/` | Ed25519 deploy-key generation (BouncyCastle), OpenSSH public-key rendering, PKCS#8 PEM round-trip (public key derives from the private key) |
| `MirrorUrlValidator` | `mirror/` | SSRF guard for targets (scheme, own-host loop check, non-public address rejection) |
| `web/MirrorResource` | `web/` | Owner-only form endpoints under `/repos/{owner}/{name}/mirrors`; non-owners get 404 |
| Settings template | `RepositoryResource/settings.html` | Mirrors panel (owner-only, under the Settings tab): list + status, deploy-key display, add/push-now/delete forms; `MirrorResource` redirects back to `…/settings` |

## Trigger flow

Both receive paths install the same post-receive hook chain
(`GitHttpServlet.createReceivePack`, `GitSshCommandFactory`):
`FederationPushService.onPush` → `IssueCommitCloser.onPush` → `MirrorService.onPush`.
The mirror hook runs on the Git worker thread without a CDI request context, so it
activates one (same pattern as `FederationPushService`), catches everything, and only
*enqueues* — the incoming push can never be slowed down or failed by mirroring.

## Queue design

A dedicated `mirror_sync` table rather than a generalized `federation_delivery`: the
payload is just a mirror reference (push the current state), not an ActivityPub
document, and coupling the two queues would force fake signer/inbox columns onto mirror
rows. The drain/backoff/dead-letter mechanics deliberately mirror `DeliveryService`
(10 s drain, `1m·2^n` capped at 1 h, `FAILED` after `max-attempts`).

Coalescing invariant: **at most one `PENDING` sync per mirror**. Enqueue pulls an
existing pending row forward instead of inserting; this is lossless because a sync
always pushes the repository's state at attempt time, not a captured delta.

## Mirror push semantics

JGit only (consistent with the rest of the codebase — no shelling out). JGit's
`PushCommand` has no `--mirror`, so `MirrorPusher` builds the update set manually:

1. read the remote's refs via `Transport.openFetch()` (upload-pack advertisement),
2. force-update every local `refs/*` (symbolic refs skipped),
3. delete every remote `refs/*` with no local counterpart (`RemoteRefUpdate` with null
   source = deletion),
4. `Transport.push(...)`; statuses `OK`/`UP_TO_DATE`/`NON_EXISTING` count as success,
   everything else is collected into the error message.

The network push runs inside the `attempt()` transaction — same trade-off as
`DeliveryService.attempt()` (simplicity over holding no tx during I/O); revisit both
together if it ever becomes a problem.

## Decisions

- **Secrets encrypted at rest, fail closed.** `encrypted_secret` goes through
  `EncryptedStringConverter` (AES-256-GCM, fresh IV per write, `enc1:` version prefix).
  Without `gitshark.secret-key`, mirror creation throws — plaintext storage is not a
  fallback. `FederationKey.privatePem` is still plaintext; migrating it to the converter
  is a known follow-up.
- **Ed25519 via BouncyCastle, not the JDK.** BC is already a dependency and registered
  at build time for native images (`quarkus.security.security-providers=BC`); the JDK's
  SunEC Ed25519 is less certain under GraalVM. The OpenSSH public-key line is encoded
  manually (RFC 8709 wire format) to avoid depending on sshd's provider detection.
- **Host keys: accept-new + pin.** First successful contact stores the server key on the
  mirror row; later syncs require an exact match. Chosen over "verify against known_hosts"
  (nothing to seed it from) and over "always accept" (permanent MITM exposure).
- **SSH client = JGit's Apache MINA bridge** (`org.eclipse.jgit.ssh.apache`, moved from
  test to compile scope) with a per-push `SshdSessionFactoryBuilder`: per-mirror in-memory
  key provider, temp home dir, custom `ServerKeyDatabase` implementing the pinning.
- **Owner-only, hidden.** All mirror endpoints 404 for non-owners (not 403), matching the
  repository-hiding convention. Secrets are accepted once and never rendered back.
- **Loop protection** compares the target host against the federation `base-url` host
  (the only configured self-identity available); plus the standard non-public-address
  rejection shared conceptually with `RemoteUrlGuard`. The DNS check runs once at mirror
  creation, so a DNS-rebinding TOCTOU (public IP at validation, private IP at a later
  sync) is theoretically possible — accepted for now because it matches the app-wide
  practice for outbound targets; re-validating per attempt would be the fix.
- **Per-push SSH homes are temp directories and are deleted after every attempt** —
  the drain loop runs forever, so leaking one directory per attempt would grow
  unbounded (regression-tested in `MirrorSshPushTest`). No secret material is written
  there; the private key is supplied in-memory.

## What works today

- HTTPS and SSH mirrors end-to-end (tests replicate to a second local repo over real
  smart-HTTP and the embedded SSH server, including branch deletions)
- Async decoupling, coalescing, retry/backoff, dead-letter + re-enqueue on next push
- Deploy-key generation/display, host-key pinning, encrypted-at-rest secrets
- Manual "push now", delete (cascades queue rows and secrets)

## What still needs to be implemented

- Enable/disable toggle in the UI (`push_mirror.enabled` exists and is honored, but the
  UI exposes no toggle)
- Replacing credentials in place (today: delete + re-create)
- Admin-level outbound target allowlist/denylist (issue #11 lists it as optional)
- Migrating `FederationKey.privatePem` to `EncryptedStringConverter`
- Mirror status surfaced via REST API / MCP (UI only today)
