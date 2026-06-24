## ADDED Requirements

### Requirement: Dashboard aggregates notifications for the current user

The system SHALL render a notifications section on the authenticated dashboard that lists items
awaiting the current user's attention (for example: issues assigned to them, merge requests assigned
to them). The section SHALL be populated by querying all registered notification sources for the
current user and combining their results.

#### Scenario: Notifications rendered for an authenticated user

- **WHEN** an authenticated user views the dashboard
- **AND** one or more registered sources return items for that user
- **THEN** the notifications section lists those items, each linking to its target

#### Scenario: No notification sources registered

- **WHEN** the dashboard is rendered and no notification sources are registered
- **THEN** the notifications section renders an explicit empty state and produces no error

#### Scenario: Anonymous users see no notifications section

- **WHEN** an unauthenticated request reaches `/`
- **THEN** the landing page is served and no notifications are queried

### Requirement: Notification sources are pluggable

The system SHALL define a `NotificationSource` abstraction that any feature can implement to contribute
items to the dashboard, and the aggregating service SHALL discover all such implementations at runtime
without further changes to the dashboard. A source SHALL receive the current user and return zero or
more notification items.

#### Scenario: New source contributes without dashboard changes

- **WHEN** a new feature provides a `NotificationSource` implementation
- **THEN** its items appear in the dashboard notifications section without modifying the dashboard code

#### Scenario: Zero registered sources is a valid state

- **WHEN** the application starts with no `NotificationSource` implementations
- **THEN** the dashboard renders successfully with an empty notifications section

### Requirement: A notification item carries enough data to render and link

The system SHALL model a notification item with at least: a category/type (e.g. assigned issue,
assigned merge request), a human-readable title, the associated repository (when applicable), and a
target URL the user can follow.

#### Scenario: Item renders with title and link

- **WHEN** a source returns a notification item
- **THEN** the dashboard displays its title and category and links to its target URL

### Requirement: One failing source does not break the dashboard

The system SHALL isolate notification sources so that an error in one source does not prevent the
dashboard from rendering or suppress items from other sources.

#### Scenario: A source throws

- **WHEN** one registered source raises an error while producing items
- **THEN** the dashboard still renders, items from other sources are shown, and the failure is logged

### Requirement: Notifications are ordered deterministically

The system SHALL present aggregated notification items in a stable, predictable order so the section
does not reshuffle between identical requests.

#### Scenario: Stable ordering across requests

- **WHEN** the same user loads the dashboard twice with unchanged underlying data
- **THEN** the notification items appear in the same order both times
