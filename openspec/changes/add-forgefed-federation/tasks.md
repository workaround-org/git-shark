## 1. Foundation: config, schema, model

- [x] 1.1 Add `gitshark.federation.enabled` (default false), `gitshark.federation.base-url`, and
  `gitshark.federation.peer-allowlist` to `application.properties` (+ `%test` values); add a
  `FederationConfig` that validates base-url is absolute and non-loopback when enabled (fail closed)
- [x] 1.2 Write Flyway migration `V2__federation.sql`: `federation_keys` (actor_type, actor_ref,
  public_pem, private_pem, created_at), `remote_actors` (actor_id, inbox, public_key_pem, fetched_at),
  `repository_followers` (repository_id, follower_actor_id, created_at), `federation_inbox`
  (activity_id unique, received_at), `federation_delivery` (id, target_inbox, payload, attempts,
  next_attempt_at, state)
- [x] 1.3 Add Panache entities + `Repo` interfaces for the new tables under `de.workaround.model`
- [x] 1.4 `EntityPersistenceTest`-style test confirming the new entities persist and Hibernate
  validates against the migration (5/5 green; V2 migration applies and validates)

## 2. Actors, keys, WebFinger (capability: federation-actors)

- [x] 2.1 Tests first: actor JSON-LD shape (`@context`, `type`, `inbox`/`outbox`/`followers`,
  `publicKey`) for Repository, Person, and instance Application actors; WebFinger JRD resolves a
  public repo and 404s unknown/private subjects; content negotiation returns HTML by default and
  JSON-LD for `application/activity+json`; private repo actor → 404 (`FederationActorsTest`, 8/8)
- [x] 2.2 `ActorKeyService`: lazily generate + persist RSA-2048 keypairs (JCA), PEM-encode public
  keys, reuse across restarts
- [x] 2.3 JSON-LD actor model (`ObjectNode` builders in `ActorDocuments`) with the pinned `@context`;
  builders for Repository, Person, Application actors and `OrderedCollection`
- [x] 2.4 `ActivityPubResource` under `/ap`: actor documents, `outbox`, `followers` GET endpoints;
  enforce `operational()` + PUBLIC-only + base-url-present
- [x] 2.5 Content negotiation on `GET /repos/{owner}/{name}`: `application/activity+json` → 303 to
  `/ap/...`, else existing HTML
- [x] 2.6 `WebFingerResource` at `/.well-known/webfinger`
- [x] 2.7 Used Jackson tree nodes (no per-DTO reflection needed); `application/activity+json` produced
  via explicit `Response.type(...)`. Native HTTP-client registration handled in Section 3.

## 3. Transport: signatures, client, inbox, delivery (capability: federation-transport)

- [x] 3.1 Tests first: sign→verify round-trip; tampered body/digest fails; missing/invalid signature
  → 401; off-allowlist host refused; private-IP target blocked; enqueued delivery survives "restart"
  and retries with backoff (`HttpSignaturesTest`, `RemoteUrlGuardTest`, `InboxAuthTest`,
  `DeliveryQueueTest` — 14/14; duplicate-id no-op covered by `recordAndDispatch` + Section 6)
- [x] 3.2 `HttpSignatures`: build the draft-cavage signing string `(request-target) host date digest`,
  sign with the actor key, and verify against a fetched public key; SHA-256 `Digest` header
- [x] 3.3 `ActivityPubClient`: HTTPS-only fetch of remote actors/keys with allowlist + SSRF guards
  (`RemoteUrlGuard`: block private/loopback/link-local, cap size/redirects), TTL cache into
  `remote_actors`; signed inbox POST. (Public actor docs fetched unsigned — they are public.)
- [x] 3.4 `InboxResource` POST per actor → `InboxService`: verify signature, enforce allowlist, dedup
  via `federation_inbox`, dispatch via `ActivityDispatcher`; respond `202`/`401`
- [x] 3.5 `DeliveryService` + `@Scheduled` worker: enqueue to `federation_delivery`, drain with signed
  POSTs, exponential backoff, max-attempt dead-letter
- [~] 3.6 HTTP client is JDK `java.net.http` (GraalVM-supported); no custom registration needed for
  tree-node JSON. Full native verification deferred to task 6.3.

## 4. Following (capability: federation-following)

- [x] 4.1 Tests first: verified `Follow` of a public repo records a follower and enqueues `Accept`;
  `Follow` of private/unknown repo → no follower, no Accept; `Undo`(Follow) removes the follower
  (`FederationFollowingTest`, 3/3)
- [x] 4.2 `FollowHandler`: validate object = repo actor (`LocalActors`), record follower in
  `repository_followers`, build + enqueue `Accept` to the follower's inbox
- [x] 4.3 `UndoHandler`: remove follower on `Undo`(Follow)
- [x] 4.4 `followers` collection (section 2.4 `ActivityPubResource`) reads from `repository_followers`

## 5. Push announce (capability: federation-push-announce)

- [x] 5.1 Tests first: real HTTP push to a public repo emits a `Push` (ref, old/new SHA, new commits,
  attributed to pusher) into the outbox and enqueues delivery to each follower; private repo emits
  nothing; unreachable follower does not fail the push (`FederationPushTest`, 3/3). SSH path shares
  the same `onPush` service and is covered structurally + by the Section 6 setup.
- [x] 5.2 `FederationPushService.onPush(...)` → `publish(...)`: build the ForgeFed `Push` (commits via
  `RevWalk`), assign a stable id, append to `federation_outbox`
- [x] 5.3 Invoke `onPush` from smart-HTTP receive path (`GitHttpServlet` post-receive hook) with the
  ref updates and authenticated pusher
- [x] 5.4 Invoke `onPush` from the SSH receive path (`GitSshCommandFactory` post-receive hook) through
  the same service
- [x] 5.5 Enqueue delivery of the `Push` to all followers via `DeliveryService`

## 6. End-to-end + verify

- [x] 6.1 In-process cross-instance handshake test through the real verify→dedup→dispatch path: a
  signed `Follow` from a "remote" actor (key pre-seeded in the actor cache) is verified, recorded,
  and `Accept`'d; replay is idempotent; tampering → 401 (`FederationHandshakeTest`, 3/3). A fully
  networked two-host test is deferred to 6.4 (the SSRF guard blocks loopback / requires HTTPS).
- [x] 6.2 Run `./mvnw test` — full suite green (95/95; +43 federation tests)
- [x] 6.3 Native-image smoke — PASSED. `./mvnw verify -Dnative -Dquarkus.native.container-build=true`
  built the native binary (Mandrel 25.0.3 / JDK 25, 2m4s, 115 MB) with `--link-at-build-time
  --no-fallback` and ran `SmokeIT` (2/2) against it. The flagged JCA risk did NOT materialize: the
  federation crypto (RSA keygen/`SHA256withRSA`) and `java.net.http` client are reachable in native
  with no extra security-services registration. (SmokeIT covers HTTP health + SSH banner; the signed
  federation round-trip in native is still part of the 6.4 two-host manual check.)
- [x] 6.4 Two-instance trial DONE (two JVM instances on 127.0.0.1:8090/8091, mutual allowlist, dev
  insecure flag): a signed `Follow` from B was accepted + `Accept` delivered to B; a real `git push`
  to A emitted a `Push` delivered to and signature-verified by B. This surfaced + fixed a real bug:
  `java.net.http` negotiates HTTP/2, where the signed `Host` header becomes `:authority` and the
  receiver could not reconstruct it → all signed deliveries 401'd. Fixed by forcing HTTP/1.1 on the
  client and reconstructing `host` from the request authority on verify (`FederationDeliveryRoundTripTest`
  regression guard). Outbound "follow a remote repo" remains a follow-up (issue #3).
- [x] 6.5 `README.md` updated: Federation section, `GITSHARK_FEDERATION_*` config, persisted tables,
  permanent-actor-ID warning, and the note that non-git-shark interop is untested.
