## Context

git-shark is a single Quarkus 21 service (JGit + Apache MINA SSHD, PostgreSQL via Flyway, OIDC login,
Qute UI) that compiles to a GraalVM native image. Entities today are `User`, `Repository`, `SshKey`,
`AccessToken` (see `V1__init.sql`); Git access goes through `GitHttpServlet` (smart HTTP) and
`SshGitBridge` (SSH). There is no issue, PR, or notification model.

ForgeFed is an ActivityPub extension. ActivityPub server-to-server ("S2S") requires: actors with
`inbox`/`outbox`/`followers` collections published as JSON-LD; actor discovery (WebFinger + content
negotiation); authenticated delivery via **HTTP Signatures** (each actor has a keypair, the public
key is published in the actor document, requests are signed with the private key and verified by the
receiver fetching the sender's key). ForgeFed adds forge vocabulary: the `Repository` actor and the
`Push` activity are the two we need for the first goal.

Constraints: must stay native-image-safe (no runtime-classpath JSON-LD reflection magic, manual
reflection registration for new model classes), reuse the already-present BouncyCastle for crypto,
and not regress local Git access or OIDC. Actor IDs are absolute URLs and must never change once
published, so a configured **public base URL** is foundational.

## Goals / Non-Goals

**Goals:**
- Two git-shark instances federate: A follows a repo on B and receives B's `Push` activities.
- Standards-compliant ActivityStreams + ForgeFed JSON-LD and HTTP Signatures, so non-git-shark
  ForgeFed software *could* interoperate later.
- All federation is optional and off by default; bounded by a peer allowlist on first rollout.
- Native-image compatible; no new heavyweight dependencies if avoidable.

**Non-Goals:**
- Tickets/issues/merge-requests federation (no local feature to federate yet).
- Verified interop with Forgejo/Vervis/Mastodon (kept possible, not tested).
- Cross-instance push/write access or remote-commit mirroring; following is read-only awareness.
- Rich timeline/notification UI.

## Decisions

**Decision: A dedicated `/ap` route namespace for actor documents, with content negotiation on
existing pages.** Actor IDs are `https://{host}/ap/repos/{owner}/{name}` (Repository),
`https://{host}/ap/users/{username}` (Person), and `https://{host}/ap/instance` (the instance
Application actor). `GET /repos/{owner}/{name}` with `Accept: application/activity+json` 303-redirects
(or directly serves) the actor document, while HTML stays the default. Rationale: keeps the JSON-LD
representation cleanly separable and cache-controllable, avoids overloading the HTML controllers with
serialization branching, and gives a stable, greppable federation surface. Alternative — serve
JSON-LD inline from `RepositoryResource` by sniffing `Accept` — rejected: muddies the UI controller
and complicates native reflection scoping.

**Decision: Per-actor RSA-2048 keypairs, generated lazily and stored in Postgres.** Each Repository,
each federating User, and the instance actor get an RSA keypair (RSA chosen for maximum ActivityPub
interop; Ed25519 is patchy across the fediverse). Keys are generated on first federation use and
cached. Rationale: matches Mastodon/ForgeFed convention so signatures verify everywhere; BouncyCastle
(already a dependency) does the crypto. Alternative — one instance-wide key signing on behalf of all
actors — rejected: breaks the actor model and many receivers reject key/actor mismatch.

**Decision: HTTP Signatures (draft-cavage, `rsa-sha256`) implemented in-house over BouncyCastle/JCA.**
Sign outbound POSTs over `(request-target) host date digest` (+ `content-type`); verify inbound by
parsing the `Signature` header, fetching the named `keyId` actor, and checking the digest + signature.
Rationale: it is a small, well-specified algorithm; no maintained native-image-friendly Java library
is worth a new dependency. Alternative — pull in a signing library — rejected for native-image risk
and dependency weight.

**Decision: JSON-LD by hand-built Jackson trees against a pinned `@context`, not a JSON-LD processor.**
We emit/consume compacted documents with a fixed `@context` (`https://www.w3.org/ns/activitystreams`,
`https://w3id.org/security/v1`, `https://forgefed.org/ns`). Serialization uses plain POJOs/`ObjectNode`
via the existing Jackson; we do **not** do generic JSON-LD expansion/normalization. Rationale: full
JSON-LD processing is heavy and native-image-hostile, and S2S interop in practice relies on the
agreed compacted shape. Alternative — Titanium/jsonld-java — rejected: size, reflection, and
overkill for fixed vocabularies.

**Decision: Persisted outbound delivery queue drained by a Quarkus scheduled worker.** Outgoing
activities are written to a `federation_delivery` table (target inbox, payload, attempts,
next_attempt_at, state) inside the triggering transaction, then a `@Scheduled` worker POSTs them with
HTTP-Signature auth and exponential backoff, marking delivered/failed. Rationale: delivery must
survive restarts and tolerate offline peers; doing it inline on the push path would couple Git
latency to remote availability. Alternative — fire-and-forget async — rejected: lost activities on
crash/peer downtime, no retry.

**Decision: Push activity is triggered by a post-receive signal from both transports.** `GitHttpServlet`
and `SshGitBridge` already mediate every push; both invoke a single `FederationPushService.onPush(repo,
refUpdates)` after a successful receive-pack. It builds a ForgeFed `Push` object (ref, old/new SHAs,
the new commits) attributed to the pusher's Person actor, appends it to the repo `outbox`, and
enqueues delivery to each follower inbox. Rationale: one choke point, both transports covered, no
reliance on on-disk Git hooks (native-image and packaging friendly). Alternative — JGit
`PostReceiveHook` scripts on disk — rejected: fragile under native packaging and container layout.

**Decision: Inbound activities are deduplicated and signature-gated before processing.** Every inbox
POST must carry a valid HTTP Signature from an allowlisted peer; the activity `id` is recorded in
`federation_inbox` for idempotency; only `Follow`, `Undo`(`Follow`), and `Accept` are processed in
this change (others are stored/ignored). Rationale: idempotent, secure inbox is the backbone of S2S.
Alternative — process before dedup — rejected: replay and double-processing.

**Decision: Federation is opt-in and allowlist-bounded.** `gitshark.federation.enabled` (default
false), `gitshark.federation.base-url`, and `gitshark.federation.peer-allowlist` (host list; empty =
deny all remote). Repositories federate only when `PUBLIC`. Rationale: safe, incremental rollout for
the git-shark↔git-shark first goal; the allowlist can later be relaxed to open federation.

## Risks / Trade-offs

- **[Hand-rolled HTTP Signatures are easy to get subtly wrong → interop/security bugs]** → narrow,
  well-tested implementation against the draft-cavage signing string; round-trip tests A↔B; reject on
  any verification failure (fail closed).
- **[SSRF / malicious actor URLs when fetching remote keys]** → fetch only over HTTPS to hosts on the
  peer allowlist, block private/loopback/link-local IPs, cap response size and redirects.
- **[Native image breakage from new Jackson model + HTTP client]** → register reflection for the
  federation DTOs and add the client to native config; keep an integration test that runs the native
  binary path in CI (existing native profile).
- **[Actor IDs become permanent the moment they federate]** → require an explicit, validated
  `base-url`; refuse to emit actor documents if it is unset/localhost while `enabled=true`.
- **[Delivery worker storms a downed peer]** → exponential backoff with a max attempt cap and a
  dead-letter state; per-host serialization.
- **[Key compromise]** → keys are per-actor and rotatable (regenerate + republish); document the
  rotation path even if automation is later.

## Migration Plan

Additive. One new Flyway migration (`V2__federation.sql`) creates `federation_keys`,
`remote_actors`, `repository_followers`, `federation_inbox`, `federation_delivery`. No existing table
changes. Feature ships **disabled** (`gitshark.federation.enabled=false`); enabling it on an instance
with a configured `base-url` + allowlist is the activation step. Rollback = disable the flag (actors
stop being served, queue drains/stops); the migration is forward-only but inert when disabled.

## Open Questions

- Do Users federate as `Person` actors in this first cut, or only Repositories (with pushes attributed
  to a bare actor URI)? Leaning: minimal `Person` actor so `Push.attributedTo` resolves.
- Actor document delivery for `GET /repos/...` with `application/activity+json`: 303-redirect to `/ap/...`
  vs. serve inline. Leaning redirect for clean separation.
- Followers collection visibility: public vs. authenticated-only. Leaning public (read), matching most
  fediverse software.
- Do we publish `nodeinfo` (`/.well-known/nodeinfo`) now for peer capability discovery, or defer? Likely
  defer to a follow-up unless a peer needs it.
