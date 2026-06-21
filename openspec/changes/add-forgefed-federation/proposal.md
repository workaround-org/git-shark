## Why

git-shark today is an island: every instance hosts its own repositories, users, and access, with no
way for someone on one instance to follow, watch, or interact with a repository on another. We want
**federation** so that two git-shark instances can talk to each other — a user on instance A can
follow a repository on instance B and see its activity (pushes), without an account on B.

We adopt [ForgeFed](https://forgefed.org) (an ActivityPub extension for software forges) rather than
inventing a private protocol. The immediate, concrete goal is **git-shark ↔ git-shark**, but by
speaking standard ActivityStreams + ForgeFed vocabulary over ActivityPub server-to-server, we stay
open to interoperating with other ActivityPub/ForgeFed software (Forgejo, etc.) later — that
interop is explicitly low priority now, but must not be designed out.

## What Changes

- Expose git-shark Repositories (and Users) as **ForgeFed/ActivityPub actors** with stable IDs,
  signing keys, and the required `inbox` / `outbox` / `followers` collections, discoverable via
  content negotiation (`application/activity+json`) and **WebFinger**.
- Implement **server-to-server transport**: sign outgoing requests with HTTP Signatures, verify
  incoming signatures by fetching+caching the remote actor's public key, accept activities at
  actor `inbox` endpoints, and deliver outgoing activities through a persisted, retrying queue.
- Implement the **Follow flow**: a remote actor sends `Follow` → repository actor responds `Accept`,
  is added to the repository's `followers`; `Undo`(`Follow`) removes it.
- Implement the **Push announce flow**: when commits are pushed to a federated repository, publish a
  ForgeFed `Push` activity to the repository's `outbox` and deliver it to all followers, so a remote
  instance learns about new commits.
- Add a per-instance **federation configuration**: an enable switch, the instance's public base URL
  (actor IDs are absolute and must be stable), and an instance-peering **allowlist** so the first
  rollout can be limited to trusted git-shark peers.

## Capabilities

### New Capabilities

- `federation-actors`: Repository and User exposed as ActivityPub/ForgeFed actors — JSON-LD actor
  documents, per-actor RSA signing keypairs, `inbox`/`outbox`/`followers` collections, an
  instance-level application actor, and WebFinger (`/.well-known/webfinger`) discovery via content
  negotiation, all native-image compatible.
- `federation-transport`: ActivityPub server-to-server delivery — HTTP Signature signing of outbound
  requests and verification of inbound ones, remote actor/key fetching with caching and SSRF
  guards, inbox receipt + validation + deduplication, and a persisted outbound delivery queue with
  retry/backoff.
- `federation-following`: Follow / Accept / Undo(Follow) handshake between a repository actor and a
  remote actor; the repository's `followers` collection is the delivery list for its activities.
- `federation-push-announce`: On push to a federated repository, build and persist a ForgeFed `Push`
  activity, place it in the repository `outbox`, and fan it out to followers via the delivery queue.

### Modified Capabilities

<!-- No archived main specs exist yet (create-git-platform is not archived), so federation is added
     as new capabilities. The git push hook integration (git-smart-http / git-ssh-access) and the
     repository actor route (web-ui content negotiation) extend pending capabilities; those touch
     points are captured inside this change's specs rather than as separate delta specs. -->

## Impact

- **New code** under `de.workaround.federation.*`: actor document model + serializers, WebFinger
  endpoint, HTTP Signature sign/verify, an ActivityPub client (remote actor fetch + inbox POST),
  inbox/outbox JAX-RS resources, a delivery worker, and the Push/Follow/Accept activity builders.
- **Database (new Flyway migration)**: actor signing keys, remote-actor cache, repository followers,
  received-activity log (dedup), and the outbound delivery queue.
- **Git transport hooks**: the smart-HTTP (`GitHttpServlet`) and SSH (`SshGitBridge`) push paths must
  emit a post-receive signal that triggers the `Push` activity. Content negotiation on
  `GET /repos/{owner}/{name}` (and user pages) returns the actor JSON-LD for `application/activity+json`.
- **Config**: new `gitshark.federation.*` properties (enabled flag, public base URL, peer allowlist);
  an HTTP client for outbound federation; scheduled delivery worker. Actor IDs depend on a correct,
  stable public base URL.
- **Native image**: new Jackson-serialized JSON-LD model classes and the federation HTTP client need
  reflection/resource registration alongside the existing JGit/MINA config; crypto reuses the
  already-present BouncyCastle.
- **Security**: signature verification is mandatory on inbound activities; remote fetches are
  SSRF-guarded; the peer allowlist bounds the initial blast radius. No change to OIDC login or local
  Git access control.

## Non-Goals

- Federating issues/tickets or merge/pull requests — git-shark has no issue or PR features yet, so
  ForgeFed `Offer`(`Ticket`) / patch flows are out of scope until those exist.
- Interop testing against non-git-shark software (Forgejo, Vervis, Mastodon). We stay
  standards-compliant so it remains possible, but it is not validated here.
- Federated identity/login, cross-instance authorization to push, or pulling remote commits into a
  local mirror. Following is read-only awareness of activity, not write access.
- Inbox UI/timeline beyond what is needed to prove delivery; rich notification UX is later.
