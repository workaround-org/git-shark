## ADDED Requirements

### Requirement: A successful push to a federated repository emits a Push activity

When commits are received on a `PUBLIC` repository over either Git transport (smart HTTP or SSH) and
federation is enabled, the system SHALL build a ForgeFed `Push` activity describing the updated ref,
the previous and new commit ids, and the newly received commits, attributed to the pusher's Person
actor.

#### Scenario: Push over HTTP emits a Push activity

- **WHEN** a user pushes new commits to a public repository over smart HTTP
- **THEN** the system builds a `Push` activity referencing the repository, the updated ref, the
  old/new commit SHAs, and is attributed to the pusher

#### Scenario: Push over SSH emits a Push activity

- **WHEN** a user pushes new commits to a public repository over SSH
- **THEN** the system builds an equivalent `Push` activity through the same code path

#### Scenario: Push to a private repository emits nothing

- **WHEN** a push targets a `PRIVATE` repository, or federation is disabled
- **THEN** the system does not build or deliver any activity

### Requirement: Push activities are added to the repository outbox

The system SHALL append each emitted `Push` activity to the repository actor's `outbox` ordered
collection with a stable activity `id`.

#### Scenario: Push appears in the outbox

- **WHEN** a `Push` activity has been emitted
- **THEN** it is present in the repository's `outbox` collection with a resolvable activity `id`

### Requirement: Push activities are delivered to followers

The system SHALL enqueue delivery of each emitted `Push` activity to the inbox of every actor in the
repository's `followers` collection, using the signed, retrying delivery transport.

#### Scenario: Followers are notified of a push

- **WHEN** a `Push` activity is emitted for a repository that has followers
- **THEN** the system enqueues a signed delivery of the activity to each follower's inbox

#### Scenario: A failed delivery does not block the push

- **WHEN** a follower's inbox is unreachable at push time
- **THEN** the Git push still completes successfully and the delivery is retried asynchronously
