# federation-following Specification

## Purpose

Allow remote actors to follow git-shark repository actors, respond with `Accept`, support `Undo` of a
follow, and use the followers collection as the delivery audience for repository activities.

## Requirements

### Requirement: A remote actor can follow a repository

The system SHALL accept a `Follow` activity addressed to a repository actor's inbox, whose `object`
is the repository actor `id`, from an allowlisted, signature-verified remote actor, and SHALL add
that actor to the repository's `followers` collection.

#### Scenario: Follow is accepted and recorded

- **WHEN** a verified remote actor POSTs a `Follow` of a public repository to its inbox
- **THEN** the system records the remote actor in the repository's `followers` collection

#### Scenario: Follow of a private or unknown repository is refused

- **WHEN** a `Follow` targets a private or non-existent repository
- **THEN** the system does not add a follower and does not send an `Accept`

### Requirement: The repository responds with Accept

After recording a follower, the system SHALL deliver an `Accept` activity, whose `object` is the
original `Follow`, from the repository actor to the follower's inbox.

#### Scenario: Accept is delivered to the follower

- **WHEN** a `Follow` has been accepted and the follower recorded
- **THEN** the system enqueues an `Accept` activity for delivery to the follower's inbox referencing
  the original `Follow`

### Requirement: A follower can undo its follow

The system SHALL accept an `Undo` activity whose `object` is a previously accepted `Follow` and SHALL
remove the actor from the repository's `followers` collection, stopping further activity delivery to
it.

#### Scenario: Undo removes the follower

- **WHEN** a current follower POSTs a verified `Undo` of its earlier `Follow`
- **THEN** the system removes that actor from the `followers` collection
- **AND** subsequent repository activities are no longer delivered to it

### Requirement: The followers collection is the delivery audience

The system SHALL treat the repository's `followers` collection as the set of inboxes to which the
repository's published activities are delivered.

#### Scenario: Followers receive repository activities

- **WHEN** the repository publishes an activity to its outbox
- **THEN** the system enqueues delivery to the inbox of every actor in its `followers` collection
