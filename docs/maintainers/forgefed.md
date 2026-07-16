# ForgeFed in git-shark: architecture and implementation notes

Maintainer-facing documentation for the federation subsystem: how it is built,
why it is built that way, what works today, and what is still missing. For
operating it see the [deployment guide](../admins/federation.md); for the user
view see the [user guide](../users/federation.md); for where the subsystem is
headed see the [federated collaboration roadmap](federation-roadmap.md).

git-shark speaks [ForgeFed](https://forgefed.org), the forge-federation
vocabulary on top of [ActivityPub](https://www.w3.org/TR/activitypub/). The
implementation is hand-rolled on Jackson + JAX-RS — there is no maintained Java
ForgeFed/ActivityPub library, and the subset we need (a handful of activity
types, HTTP Signatures, WebFinger) is small enough that a dependency would cost
more than it saves.

---

## Component map

Everything lives in `src/main/java/de/workaround/federation/`:

| Component | Role |
|---|---|
| `FederationConfig` | Central switch + validated config. `operational()` is the guard every code path checks |
| `ActorUris` / `LocalActors` | Build and parse actor IDs from the base URL |
| `ActorDocuments` | JSON builders for actor documents and activities (`Follow`, `Accept`, `Undo`, `Push`) |
| `ActorKeyService` | Lazy per-actor RSA keypair creation, stored in `federation_keys` |
| `HttpSignatures` | draft-cavage signing and verification (SHA256withRSA) |
| `RemoteUrlGuard` | SSRF guard for every outbound URL |
| `ActivityPubClient` | Signed fetches: remote actors (cached), public keys, WebFinger resolution |
| `WebFingerResource` | `GET /.well-known/webfinger` — actor discovery |
| `ActivityPubResource` | `GET /ap/**` — actor documents, outbox and followers collections |
| `InboxResource` / `InboxService` | `POST /ap/**/inbox` — verify signature, dedup, dispatch |
| `ActivityDispatcher` | Routes verified inbound activities by `type` to a handler |
| `FollowHandler` / `UndoHandler` | Inbound: remote actor (un)follows a local repository |
| `AcceptHandler` | Inbound: remote accepts a `Follow` we sent — flips our follow to `ACCEPTED` |
| `PushHandler` | Inbound: stores `Push` from repositories local users follow |
| `RemoteFollowService` | Outbound: follow/unfollow a remote repository or user, push feed query |
| `RemoteRepositoryDirectory` | Outbound: reads a remote `Person`'s `repositories` collection (fan-out source for follow-a-user) |
| `FederationResyncScheduler` | Periodic add-only re-scan of followed users' repositories (picks up repos created after the follow) |
| `FederationPushService` | Outbound: fans out `Push` to followers from the git post-receive hook |
| `DeliveryService` | Persisted outbound queue with retry/backoff/dead-letter |

Web UI: `web/FollowingResource` + Qute template (`/following` page). Persistence
in `model/` (`FederationKey`, `RemoteActor`, `RepositoryFollower`,
`RemoteFollow`, `RemoteUserFollow`, `ReceivedPush`, `InboxActivity`,
`OutboxActivity`, `DeliveryTask`), schema in `db/migration/V2__federation.sql`,
`V9__federation_following.sql`, and `V20__federation_user_follows.sql`.

## Actor model

Three local actor types, all rooted at `gitshark.federation.base-url`:

| Actor | Type | ID | WebFinger |
|---|---|---|---|
| Public repository | ForgeFed `Repository` | `/ap/repos/{owner}/{name}` | `acct:owner/name@host` |
| User | `Person` | `/ap/users/{username}` | `acct:username@host` |
| Instance | `Application` | `/ap/instance` | — |

Each has its own inbox; repositories and users also expose `outbox`,
repositories additionally a `followers` collection, and users a `repositories`
collection (`…/ap/users/{username}/repositories`) listing that user's public
repository actors for cross-instance discovery. Private repositories are
invisible to every federation endpoint — visibility is checked at the resource
layer, not filtered in templates.

## Data flow

**Outbound follow** (`RemoteFollowService.follow`): input is a handle
(`owner/name@host`, resolved via WebFinger) or a direct actor URL → fetch the
remote actor → persist a `RemoteFollow` row in state `PENDING` → enqueue a
signed `Follow`. The remote's `Accept` arrives at the user's inbox and
`AcceptHandler` flips the row to `ACCEPTED` — but only when the accepting actor
matches the one we followed. Unfollow enqueues `Undo(Follow)` and deletes the
row.

**Outbound follow-a-user** (`RemoteFollowService.followUser`): input resolves to
a remote `Person` actor id → `RemoteRepositoryDirectory` reads that Person's
`repositories` collection → a `RemoteUserFollow` row is persisted and one
repository follow is fanned out per public repo (each tagged with
`viaUserActorId`), reusing the ordinary follow path above. The set is a snapshot
at follow time. `unfollowUser` undoes every tagged repository follow, then
removes the `RemoteUserFollow`. The `/following` page groups repositories under
their followed user; directly-followed repositories are listed separately.
`FederationResyncScheduler` re-runs `RemoteRepositoryDirectory` for every
followed user on a configurable interval
(`gitshark.federation.user-resync-interval`, default 5m) and follows any new
public repository — add-only, so repos that disappear remotely are left in place.

**Inbound follow** (`FollowHandler`): a remote actor follows one of our public
repositories → persist `RepositoryFollower` → enqueue a signed `Accept` back.
`UndoHandler` removes the follower again.

**Push fan-out** (`FederationPushService`): both git transports (smart HTTP and
SSH) call `onPush` from the JGit post-receive hook. For each updated branch on a
PUBLIC repository it builds a ForgeFed `Push` (old/new ref ids plus up to 50
commit ids), records it in the outbox, and enqueues one delivery per follower.
Runs on a git worker thread, so it activates its own CDI request context and
never lets an exception escape into the git path.

**Inbound push** (`PushHandler`): stored into `received_pushes` only when at
least one local user follows the sending actor; everything else is dropped.
Deduplicated by activity id (application check + DB unique constraint). The
`/following` page renders the newest 50 across a user's follows.

**Inbound pipeline** (`InboxService.receive`) — fails closed, any failure is a
`401` with no processing:

1. Parse the `Signature` header; extract the signer host from its `keyId`.
2. Reject unless that host is on the peer allowlist.
3. Fetch the signer's public key (via the cached remote-actor fetch, itself
   SSRF-guarded and allowlist-bound).
4. Verify the signature over method, raw path, headers, and body digest, with
   date-skew checking.
5. Dedup by activity id in `federation_inbox`, then dispatch inside the same
   transaction. Unknown activity types are recorded and ignored.

**Outbound delivery** (`DeliveryService`): activities are never sent inline.
They are persisted to `federation_delivery` and drained by a scheduler every
10 s; failures retry with exponential backoff (1 m doubling to a 1 h cap) and
dead-letter as `FAILED` after `max-attempts` (default 8), preserving the last
error for operators.

## Implementation decisions

Decisions that shaped the subsystem, with the reasoning — so future changes
don't accidentally undo them:

- **Fail-closed operational gate.** Actor IDs are absolute URLs derived from
  `base-url` and are *permanent* once another server has stored them. So
  federation refuses to emit anything until `enabled=true` AND a valid,
  non-loopback base URL is set (`FederationConfig.operational()`). The
  alternative — defaulting to whatever host the request came in on — would
  publish throwaway IDs that break every follow relationship on rename.
- **Mutual peer allowlist, both directions.** Inbound activities must be signed
  by a key on an allowlisted host; outbound fetches/deliveries only go to
  allowlisted hosts. Empty list denies everything. This bounds the first
  git-shark↔git-shark rollout to explicitly trusted peers instead of open
  federation; open federation is a policy decision to make later, not a default.
- **SSRF guard on every outbound URL** (`RemoteUrlGuard`): HTTPS only, host
  allowlisted, resolved address must not be loopback/link-local/private/
  multicast. Federation fetches URLs supplied by remote servers (inbox URLs,
  key ids, WebFinger targets), which is a textbook SSRF vector into the
  deployment network. `dev-allow-insecure` relaxes scheme and address classes
  for single-machine trials but **never** the allowlist.
- **Persisted delivery queue instead of fire-and-forget.** Remote instances go
  down; a synchronous send from a request (or worse, a git hook) thread would
  lose activities and add latency to pushes. Queue + backoff + dead-letter makes
  delivery observable (`federation_delivery` is the operator's debugging
  surface) and keeps the git path fast.
- **Per-actor RSA-2048 keys, generated lazily, stored in the DB**
  (`federation_keys`, keyId `<actor-id>#main-key`). Lazy generation means no
  key ceremony at repo/user creation; DB storage means no filesystem key
  management in a container deployment. Cost: the DB now holds private keys —
  documented in the deployment guide.
- **Signing identity: `Follow` is signed by the user's `Person` actor**, since
  following is a user action; `Accept` and `Push` are signed by the repository
  actor that owns the relationship. (Decision recorded in issue #3.)
- **draft-cavage HTTP Signatures**, not RFC 9421 — it is what the existing
  ForgeFed/Fediverse ecosystem (Forgejo, Mastodon et al.) actually verifies.
  The signature covers the `Host` header and raw path, which is why the reverse
  proxy must preserve `Host`.
- **WebFinger interop: send bare-host, accept both.** The client builds the
  `acct:` resource with the bare hostname (the WebFinger standard form) while
  addressing the endpoint at `host[:port]`; the server accepts both bare-host
  and `host:port` acct forms. This keeps us compatible with implementations
  that compare the bare host strictly *and* with port-based multi-instance dev
  setups.
- **Inbound processing is idempotent by activity id** at two layers: the
  `federation_inbox` dedup log short-circuits redelivery, and consumers with
  their own tables (e.g. `received_pushes`) additionally carry a unique
  constraint. Remote queues retry; every handler must tolerate replays.
- **Store-and-ignore unknown activity types.** Dispatch is a `switch` on
  `type` with a debug-logged default. Unknown activities still land in the
  dedup log, so adding a handler later never double-processes history.
- **Server-rendered UI only.** The `/following` page is a Qute template with
  plain form POSTs, like the rest of git-shark — no client-side JS, state
  changes visible on refresh.
- **Remote actor cache** (`remote_actors`, 6 h TTL) so signature verification
  and fan-out don't re-fetch actor documents per activity.

## What works today

- Actor documents and WebFinger discovery for repositories, users, and the
  instance; outbox and followers collections. The `Person` actor advertises and
  serves a `repositories` collection of its public repository actors.
- Inbound `Follow`/`Undo(Follow)` on public repositories, answered with a
  signed `Accept` (remote users can follow local repos).
- `Push` fan-out to remote followers from both git transports.
- Outbound follow/unfollow of remote repositories by handle or actor URL,
  including `Accept` confirmation tracking (`PENDING` → `ACCEPTED`) and the
  received-pushes feed — the `/following` UI covers all of it.
- Outbound follow/unfollow of a remote **user**: reads the `Person`'s
  `repositories` collection and fans out to a repository follow per public repo,
  shown grouped in the `/following` UI (federated-collaboration roadmap Story 1).
  A periodic `FederationResyncScheduler` re-scans followed users (add-only), so
  repositories created after the follow are picked up automatically.
- HTTP Signature signing/verification, per-actor keys, inbound dedup, peer
  allowlist, SSRF guard, delivery queue with retry and dead-letter.
- Tested git-shark↔git-shark, including a scripted local two-host trial (see
  the deployment guide). Interop with Forgejo/Vervis is expected via WebFinger
  bare-host handling but **untested**.

## What still needs to be implemented

Protocol gaps:

- **`Reject(Follow)` handling** — dispatcher ignores it, so a rejected outbound
  follow stays `PENDING` forever. Related: pending follows never expire and are
  not retried if the original `Follow` delivery dead-letters.
- **Actor lifecycle activities** — no handling of `Delete` (remote account/repo
  removal), `Move`, or `Update` (key rotation of a peer actor; the 6 h cache
  masks it briefly, then verification fails until re-fetch).
- **Shared inbox** — deliveries go per-follower inbox; N followers on one host
  mean N deliveries.
- **Visibility flips are silent** — switching a public repository to private
  (owner Settings page) stops actor exposure and push fan-out immediately
  (`FederationPushService` re-checks visibility per push), but existing remote
  followers are kept in `repository_followers` and receive no `Reject`/`Delete`;
  the remote side still lists the follow as accepted. Switching back to public
  resumes fan-out to those retained followers.
- **ForgeFed beyond Push**: `Ticket` (federated issues), patches/merge-request
  offers, `Fork`, stars/watch semantics. Issues, MRs, and comments are
  local-only today.
- **NodeInfo** endpoint and instance-level metadata for discovery.
- **Organisation actors** — organisations (shared repo namespaces, see
  `docs/users/organisations.md`) have no ActivityPub actor or WebFinger entry.
  Their names are already reserved in the shared user/org handle namespace
  (cross-table collision check on user onboarding and org creation), so a
  later `Group`-style actor cannot collide with a `Person` actor. Repositories
  owned by an org federate like any other repo actor; only the owning actor
  itself is missing.
- **RFC 9421 signatures** as the ecosystem migrates (double-knocking).

Operational gaps:

- **Key rotation** for local actors — none; a leaked key means manual DB
  surgery.
- **Rate limiting / abuse controls on the inbox** — the allowlist is currently
  the only throttle, fine for the closed rollout, insufficient for open
  federation.
- **Follower/feed UI depth** — repository pages don't show remote followers;
  the push feed is a flat newest-50 with no pagination or per-repo filtering.
- **Follow-a-user reconcile is add-only** — `FederationResyncScheduler` picks up
  repositories added after the follow, but does not *unfollow* repositories that
  the remote user made private or deleted; those stale follows linger. Also,
  `RemoteRepositoryDirectory` reads only the first collection page — it does not
  follow `next` pagination, fine for the git-shark↔git-shark scope but a gap for
  users with large repository lists or broader ForgeFed peers.
- **Delivery observability** — dead-letters are only visible via SQL; no
  admin UI or metrics.
