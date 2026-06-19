## Context

Today `HomeResource.home()` (`GET /`) calls `Templates.home(service.listVisibleTo(user), user)` for
everyone. `user` is `null` for anonymous requests (`CurrentUser.get()` returns null when the
identity is anonymous), so anonymous visitors see a repository table with no product context.

Constraints: server-rendered Qute templates, no JavaScript (the rest of the UI is JS-free), must
compile to a GraalVM native image, single shared `layout.html`. OIDC login already exists.

## Goals / Non-Goals

**Goals:**
- Show a branded landing page to unauthenticated visitors at `/`.
- Keep the authenticated repository-list experience byte-for-byte unchanged.
- Glowing ASCII-art hero using only HTML + CSS.
- Preserve anonymous access to public repositories via an explicit link.

**Non-Goals:**
- No marketing CMS, no content management, no i18n.
- No JavaScript, animations beyond CSS, or external assets/fonts/images.
- No change to auth, SSH, Git protocol, or database.

## Decisions

**Decision: Branch inside `HomeResource.home()` on authentication, not a new route.**
Keep `GET /` as the single entry. `home()` checks `currentUser.get()`: null → render
`landing.html`; non-null → render the existing repo list. Rationale: anonymous landing at the root
is the requirement; a separate `/landing` path would still need a redirect from `/`.
Alternative considered: redirect anonymous `/` → `/welcome`. Rejected — extra round-trip and a
second public route to secure for no benefit.

**Decision: Move the anonymous-reachable public repo list to an explicit link.**
The landing page links to repository browsing (e.g. `/explore`, or reuse the existing list view
behind a query/path). Rationale: the spec requires public browsing not be removed. Simplest
implementation: add an `/explore` GET that renders the current `home.html` repo table for
`service.listVisibleTo(null)`; the landing CTA points there. Alternative: keep repo list at `/`
with landing above it — rejected, muddies the hero.

**Decision: ASCII-art glow via CSS `text-shadow` layers on a `<pre>` block.**
Embed the shark ASCII art in a `<pre>` with a monospace font and stacked `text-shadow` (multiple
blur radii in an accent color) over a dark background for the glow. Rationale: pure CSS, native-safe,
no assets. Alternative: SVG filter glow — heavier markup, no benefit at this fidelity.

**Decision: Dedicated landing markup, minimal coupling to `layout.html`.**
The logged-in `layout.html` header carries authenticated nav (SSH keys, tokens, logout). The
landing page needs a different, anonymous header (Log in / self-host). Either add a `{#if user}`
guard in the layout nav, or give the landing template its own lightweight shell. Prefer guarding
the nav in `layout.html` so styling stays centralized.

## Risks / Trade-offs

- [ASCII glow renders poorly on very narrow viewports] → wrap `<pre>` in an `overflow-x:auto`
  container and scale font with `clamp()`; accept horizontal scroll on tiny screens.
- [Splitting repo list to `/explore` changes the anonymous URL people may have bookmarked] →
  acceptable for a young project; `/` still reaches public repos in one click.
- [Qute `@CheckedTemplate` requires a new `landing(...)` native method] → add it alongside `home`;
  trivial, compile-checked.

## Migration Plan

Pure additive UI change. Deploy normally; no schema or config migration. Rollback = revert the
template + `HomeResource` change. No data touched.

## Open Questions

- Exact path for public browsing: `/explore` (new) vs. a flag on `/`. Leaning `/explore`.
- Final ASCII-art content for the shark — placeholder until design-approved.
