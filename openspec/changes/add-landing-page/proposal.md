## Why

Anonymous visitors currently land on the bare repository list (`/`), which gives no sense of what
git-shark is or why it exists. First-time, unauthenticated visitors need a page that states the
product's stance and invites them to log in or self-host — not a raw table of public repos.

## What Changes

- Serve a dedicated **landing page** at `/` for unauthenticated requests, instead of the repository
  list. Logged-in users keep seeing their repository list unchanged.
- The landing page presents the product positioning:
  - "Use AI as a tool, not as a feature"
  - "Focus on co-working, not lazy feeds"
  - "Easy and painless to self-host"
- Visual treatment: dark hero background with glowing ASCII-art branding (the shark), rendered with
  server-side markup + CSS only (no JS, native-image friendly).
- Primary call to action: **Log in** (existing OIDC flow). Secondary: link to self-hosting docs/repo.
- Anonymous browsing of public repositories stays reachable via an explicit link (e.g. `/explore` or
  a "Browse public repositories" link), so no functionality is removed.

## Capabilities

### New Capabilities
- `landing-page`: Unauthenticated entry page at `/` — hero with glowing ASCII-art background, three
  value propositions, login CTA, self-host link, and a link to browse public repositories. Renders
  for anonymous users only; authenticated users are routed to the repository list.

### Modified Capabilities
<!-- No main specs exist yet (create-git-platform is not archived). web-ui requirement change for the
     anonymous `/` route is captured within this change's interaction with that pending capability;
     no separate delta spec is added here. -->

## Impact

- `web/HomeResource.java`: branch `home()` on `currentUser.get()` — anonymous → landing template,
  authenticated → existing repo list (or move repo list to `/explore`).
- New Qute template `HomeResource/landing.html` (+ shared `layout.html` may need a variant header
  without the logged-in nav).
- New CSS for the dark hero + glowing ASCII art; no new dependencies, no JavaScript.
- No database, SSH, or Git protocol changes.
