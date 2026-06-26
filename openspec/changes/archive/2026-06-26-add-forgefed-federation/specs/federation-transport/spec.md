## ADDED Requirements

### Requirement: Outbound activities are signed with HTTP Signatures

The system SHALL sign every outbound ActivityPub HTTP request with an HTTP Signature
(`rsa-sha256`, draft-cavage) using the sending actor's private key. The signed string SHALL cover at
least `(request-target)`, `host`, `date`, and a `digest` of the body, and the request SHALL include
`Signature`, `Date`, and `Digest` headers and a `keyId` referencing the actor's `publicKey` id.

#### Scenario: Delivered activity carries a valid signature

- **WHEN** the system delivers an activity to a remote inbox
- **THEN** the POST includes a `Signature` header with `keyId` set to the sending actor's public key
  id and a `Digest` matching the body

### Requirement: Inbound activities must pass signature verification

The system SHALL verify the HTTP Signature on every inbox POST by resolving the `keyId` to a remote
actor, fetching its public key, and validating the signature and body `Digest`. The system SHALL
reject (`401`) any request that is unsigned, has an invalid signature, a mismatched digest, or a
stale `Date`.

#### Scenario: Valid signature is accepted

- **WHEN** an allowlisted peer POSTs a correctly signed activity to an inbox
- **THEN** the system verifies the signature and accepts the activity (`202`)

#### Scenario: Invalid or missing signature is rejected

- **WHEN** an inbox POST has no signature or a signature that fails verification
- **THEN** the system responds `401` and does not process the activity

### Requirement: Remote actors and keys are fetched with SSRF protections

The system SHALL fetch remote actor documents and public keys only over HTTPS to hosts on the peer
allowlist, SHALL refuse private, loopback, and link-local addresses, SHALL cap response size and
redirect count, and SHALL cache fetched actors/keys with a bounded TTL.

#### Scenario: Non-allowlisted host is refused

- **WHEN** the system would fetch an actor from a host not on the peer allowlist
- **THEN** the fetch is refused and no request is made to that host

#### Scenario: Private address target is blocked

- **WHEN** a remote actor `id` or `keyId` resolves to a private/loopback/link-local IP
- **THEN** the system blocks the fetch

### Requirement: Inbox receipt is idempotent

The system SHALL record the `id` of each accepted inbound activity and SHALL ignore a redelivery of
an already-processed activity, so duplicate or replayed deliveries have no additional effect.

#### Scenario: Duplicate delivery is a no-op

- **WHEN** the same activity `id` is delivered twice
- **THEN** the system processes it once and acknowledges the duplicate without reprocessing

### Requirement: Outbound delivery is queued and retried

The system SHALL persist each outbound activity delivery (target inbox, payload, attempt count, next
attempt time, state) and SHALL drain the queue with a background worker, retrying failed deliveries
with exponential backoff up to a maximum, after which the delivery is marked failed/dead-lettered.

#### Scenario: Delivery survives a restart

- **WHEN** an activity is enqueued for delivery and the service restarts before it is sent
- **THEN** the worker still delivers it after restart

#### Scenario: Unreachable peer is retried then dead-lettered

- **WHEN** a target inbox is unreachable
- **THEN** the system retries with increasing backoff and, after the maximum attempts, marks the
  delivery failed without blocking other deliveries

### Requirement: Federation is disabled by default and bounded by an allowlist

The system SHALL treat `gitshark.federation.enabled` as false unless explicitly set, and when
enabled SHALL accept and send federation traffic only to/from hosts on
`gitshark.federation.peer-allowlist` (an empty allowlist denies all remote peers).

#### Scenario: Disabled federation serves no federation endpoints

- **WHEN** federation is disabled
- **THEN** inbox/outbox/actor/WebFinger federation endpoints are not served (or respond `404`)

#### Scenario: Off-allowlist peer is rejected

- **WHEN** an inbox POST arrives from a host not on the allowlist
- **THEN** the system rejects it
