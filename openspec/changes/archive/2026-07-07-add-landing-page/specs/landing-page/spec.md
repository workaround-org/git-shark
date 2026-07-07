## ADDED Requirements

### Requirement: Anonymous root serves the landing page

The system SHALL serve a dedicated landing page at `GET /` when the request is unauthenticated.
The system SHALL serve the existing repository list to authenticated users, so the change is
invisible to logged-in users.

#### Scenario: Anonymous visitor sees the landing page

- **WHEN** an unauthenticated client requests `GET /`
- **THEN** the system responds with `200` and renders the landing page (not the repository list)

#### Scenario: Authenticated user keeps the repository list

- **WHEN** an authenticated user requests `GET /`
- **THEN** the system renders their visible repository list, unchanged from prior behavior

### Requirement: Landing page presents product positioning

The landing page SHALL display the three value propositions as visible text:
"Use AI as a tool, not as a feature", "Focus on co-working, not lazy feeds", and
"Easy and painless to self-host".

#### Scenario: Value propositions are rendered

- **WHEN** an unauthenticated client requests `GET /`
- **THEN** the response body contains all three value-proposition statements

### Requirement: Landing page hero with branding

The landing page SHALL render a hero section with the git-shark logo and tagline, using the shared
`shark.css` design system (no page-level inline `<style>` block), so branding stays consistent with
the rest of the UI.

#### Scenario: Hero renders logo and shared stylesheet

- **WHEN** an unauthenticated client requests `GET /`
- **THEN** the response links `/shark.css`, contains the `/img/shark-logo.png` logo, and carries no inline `<style>` block

### Requirement: Landing page calls to action

The landing page SHALL provide a primary "Log in" call to action that initiates the existing OIDC
login flow, a link to self-hosting documentation/repository, and a link to browse public
repositories so anonymous browsing is not removed.

#### Scenario: Login CTA initiates OIDC flow

- **WHEN** an anonymous visitor activates the "Log in" call to action
- **THEN** the system initiates the OIDC authorization-code login flow

#### Scenario: Public repositories remain reachable

- **WHEN** an anonymous visitor activates the "Browse public repositories" link
- **THEN** the system shows the list of repositories visible to anonymous users
