# federation-actors Specification

## Purpose

Publish git-shark repositories, users, and the instance itself as ActivityPub/ForgeFed actors with
stable IDs, signing keypairs, inbox/outbox/followers collections, and WebFinger discovery, so that
they can participate in the fediverse.

## Requirements

### Requirement: Repository is published as a ForgeFed Repository actor

The system SHALL expose every `PUBLIC` repository as a ForgeFed `Repository` actor with a stable,
absolute `id` of the form `https://{base-url}/ap/repos/{owner}/{name}`. The actor document SHALL be
valid JSON-LD with `@context` including `https://www.w3.org/ns/activitystreams`,
`https://w3id.org/security/v1`, and `https://forgefed.org/ns`, and SHALL include `inbox`, `outbox`,
`followers`, `preferredUsername`, `name`, and a `publicKey` with the actor's PEM-encoded public key.

#### Scenario: Repository actor document is served via content negotiation

- **WHEN** a client requests the repository with `Accept: application/activity+json`
- **THEN** the system returns the JSON-LD `Repository` actor document with `Content-Type:
  application/activity+json` and `type` `Repository`

#### Scenario: HTML stays the default representation

- **WHEN** a browser requests `GET /repos/{owner}/{name}` with an HTML `Accept` header
- **THEN** the system returns the existing HTML repository page, not the actor document

#### Scenario: Private repositories are not federated

- **WHEN** a client requests an actor document for a `PRIVATE` repository
- **THEN** the system responds `404` and does not expose any actor document

### Requirement: Users are published as Person actors

The system SHALL expose a federating user as an ActivityPub `Person` actor with `id`
`https://{base-url}/ap/users/{username}`, an `inbox`, an `outbox`, and a `publicKey`, so that
activities attributed to that user (e.g. a push) reference a resolvable actor.

#### Scenario: Person actor resolves

- **WHEN** a client requests `GET /ap/users/{username}` with `Accept: application/activity+json`
- **THEN** the system returns a `Person` actor document with a resolvable `inbox` and `publicKey`

### Requirement: An instance application actor exists

The system SHALL expose a single instance-level `Application` actor at
`https://{base-url}/ap/instance` with its own keypair, used to sign instance-level requests such as
fetching remote actors.

#### Scenario: Instance actor is available for signing

- **WHEN** the system needs to fetch a remote actor document
- **THEN** it signs the request as the instance `Application` actor whose `publicKey` is resolvable
  at `/ap/instance`

### Requirement: Each actor has a persistent signing keypair

The system SHALL generate an RSA-2048 keypair per federating actor (repository, user, instance) on
first federation use and persist it, so the published `publicKey` is stable and the private key is
reused across restarts.

#### Scenario: Key is generated once and reused

- **WHEN** an actor federates for the first time
- **THEN** the system generates and stores a keypair
- **AND WHEN** the same actor federates again after a restart
- **THEN** the system reuses the stored keypair and publishes the same `publicKey`

### Requirement: Actors expose inbox, outbox, and followers collections

The system SHALL serve, for each actor, an `inbox` (POST endpoint for incoming activities), an
`outbox` (ordered collection of the actor's published activities), and a `followers` ordered
collection, each addressable at the URL named in the actor document.

#### Scenario: Collections are reachable

- **WHEN** a client requests an actor's `outbox` or `followers` URL with `Accept:
  application/activity+json`
- **THEN** the system returns an `OrderedCollection` JSON-LD document

### Requirement: Actors are discoverable via WebFinger

The system SHALL respond to `GET /.well-known/webfinger?resource=acct:{name}@{host}` for repository
and user actors, returning a JRD document whose `links` include a `self` link of type
`application/activity+json` pointing to the actor `id`.

#### Scenario: WebFinger resolves a repository actor

- **WHEN** a client requests `GET /.well-known/webfinger?resource=acct:{owner}/{name}@{host}` for a
  public repository
- **THEN** the system returns a `200` JRD with a `self` link to the repository actor `id`

#### Scenario: WebFinger rejects unknown subjects

- **WHEN** a WebFinger request names a subject that does not exist or is private
- **THEN** the system responds `404`

### Requirement: Federation requires a configured public base URL

When `gitshark.federation.enabled` is true, the system SHALL require a valid, absolute, non-loopback
`gitshark.federation.base-url`, and SHALL refuse to emit actor documents (failing closed) if it is
unset or points at localhost, because actor IDs are permanent once published.

#### Scenario: Missing base URL blocks actor emission

- **WHEN** federation is enabled but `base-url` is unset or a loopback address
- **THEN** the system does not serve actor documents and surfaces a configuration error
