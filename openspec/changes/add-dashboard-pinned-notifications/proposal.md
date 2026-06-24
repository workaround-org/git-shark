## Why

After logging in, users land on a single flat table of every repository visible to them (`home.html`).
There is no way to surface the handful of repositories someone actually works in day-to-day, and no
place that tells a returning user what needs their attention. As the repository count grows, the
dashboard becomes a wall of rows the user has to scan every visit. We want the post-login page to be a
real **dashboard**: quick access to the repos you care about, a feed of what's waiting on you, and the
full list still available below.

## What Changes

- Restructure the authenticated home page (`GET /`) into three stacked sections, in priority order:
  1. **Pinned repositories** — repos the user has explicitly pinned for quick access.
  2. **Notifications** — things assigned to or awaiting the user (assigned issues, assigned merge
     requests, etc.).
  3. **All repositories** — the full visible-to-user list (today's behavior).
- Add the ability to **pin / unpin a repository** per user. A pin is a per-user marker on a repository;
  pinned repos appear in the dedicated section and are excluded from (or de-emphasized in) the full
  list. Pin/unpin is reachable from the repository row and the repository overview page.
- Introduce a **pluggable notification framework**: a `NotificationSource` abstraction that the
  dashboard queries to build the notifications section. This change ships the framework and the UI
  section with **no concrete sources wired** — git-shark has no Issue or Merge Request features yet.
  When those land, they register as notification sources without further dashboard changes. Until then
  the section renders an explicit empty state.
- The anonymous landing page and `/explore` (public repo browsing) are unchanged.

## Capabilities

### New Capabilities
- `repository-pinning`: Per-user pinning of repositories — pin, unpin, list a user's pinned repos, and
  query whether a given repo is pinned. Persisted across sessions.
- `dashboard-notifications`: A pluggable notification framework (`NotificationSource` abstraction)
  plus the dashboard notifications section that aggregates and renders items awaiting the current user.
  Ships with zero registered sources and a defined empty state.

### Modified Capabilities
<!-- No main specs exist yet (create-git-platform is not archived, openspec/specs/ is empty), so the
     dashboard restructure of GET / is captured as part of this change's new capabilities rather than
     as a delta against a published capability. This mirrors the add-landing-page change. -->

## Impact

- `web/HomeResource.java`: `home()` now assembles a dashboard view-model (pinned repos, notifications,
  all repos) for authenticated users instead of a single repo list. New pin/unpin endpoints.
- New model + persistence for pins (e.g. `RepositoryPin` join entity, or a pin association on the user)
  and a Flyway migration (`db/migration/V2__...sql`).
- New `NotificationSource` interface + an aggregating service; CDI-discovered, zero implementations now.
- New/updated Qute templates: `HomeResource/home.html` split into three sections; pin controls on repo
  rows and `RepositoryResource/overview.html`.
- CSS additions in `shark.css` for the dashboard sections and empty states. No JavaScript (server-
  rendered, native-image friendly).
- No SSH, Git protocol, or auth changes.
