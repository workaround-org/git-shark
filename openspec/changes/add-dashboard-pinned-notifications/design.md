## Context

The authenticated home page is rendered by `HomeResource.home()` (`GET /`), which calls
`Templates.home(service.listVisibleTo(user), user)` — a single flat table of every repository visible
to the user. Anonymous requests already branch to the landing page (added in `add-landing-page`).

Domain model today: `User`, `Repository` (owner + `Visibility`), `SshKey`, `AccessToken`. There is **no
Issue or Merge Request concept** anywhere in the codebase. Persistence is Hibernate + Panache Next with
Flyway migrations (`db/migration/V1__init.sql`). The UI is server-rendered Qute with no JavaScript and
must compile to a GraalVM native image.

The user wants the post-login page to become a dashboard with three sections: pinned repositories,
notifications (assigned issues / merge requests / etc.), and the full repository list. Because Issues
and Merge Requests do not exist yet, the notifications section is built as a framework that ships with
zero concrete sources (decision confirmed with the user).

## Goals / Non-Goals

**Goals:**
- Restructure `GET /` (authenticated) into pinned → notifications → all-repositories sections.
- Let users pin/unpin repositories, persisted per user across sessions.
- Provide a `NotificationSource` abstraction the dashboard aggregates, with a defined empty state and
  zero sources wired now.
- Keep the page server-rendered, JS-free, and native-image safe.
- Leave the anonymous landing page and `/explore` untouched.

**Non-Goals:**
- Implementing Issues or Merge Requests (separate future changes register as notification sources).
- Drag-to-reorder pins, pin limits, or per-pin metadata beyond existence.
- Real-time / push notifications, read/unread tracking, or notification persistence.
- Any change to OIDC auth, SSH, or the Git protocol.

## Decisions

**Decision: Model a pin as a `RepositoryPin` join entity (user, repository, createdAt).**
A dedicated `@Entity` with a unique constraint on `(user_id, repository_id)` keeps pins
referentially clean and makes "is repo X pinned by user Y" and "list pins for user Y" simple Panache
queries. Add a `V2__repository_pins.sql` Flyway migration with FKs to `users` and `repositories`, both
`ON DELETE CASCADE` so deleting a repo or user clears its pins (satisfies the cleanup requirement
without application code). Alternatives considered: a `@ManyToMany` set on `User` (hides the join table,
harder to add `createdAt`/ordering later) — rejected; a boolean column on `Repository` (not per-user) —
wrong semantics.

**Decision: Pin/unpin via POST endpoints with redirect-back, no JavaScript.**
Add `POST /repos/{owner}/{name}/pin` and `POST .../unpin` (or a single toggle) on `RepositoryResource`
or `HomeResource`, each guarded by `currentUser.require()`, validating visibility via the existing
`AccessPolicy`, then `seeOther` back to the referring page. Pin controls are small `<form>` buttons on
each repo row and on `RepositoryResource/overview.html`. Rationale: matches the existing JS-free,
form-POST pattern (`repos` creation). Alternative: a fetch/AJAX toggle — rejected, violates the no-JS
constraint and native-image simplicity.

**Decision: `NotificationSource` is a CDI interface; an aggregator injects all implementations.**
Define `interface NotificationSource { List<NotificationItem> notificationsFor(User user); }` and a
`NotificationService` that injects `Instance<NotificationSource>` (CDI), calls each, and concatenates
results. `NotificationItem` is a simple record: `type/category`, `title`, optional `repository`, and
`targetUrl`. With zero implementations, `Instance` is empty and the service returns an empty list.
Rationale: new features contribute by just adding a `@ApplicationScoped` bean — no dashboard edits, no
registry to maintain. Alternative: an explicit registry list in config — more boilerplate, easy to
forget to update.

**Decision: Isolate each source; one failure must not break the dashboard.**
The aggregator wraps each `notificationsFor` call in try/catch, logs failures, and continues. Rationale:
a buggy future source (e.g. a DB hiccup in the issues source) should degrade to "fewer notifications",
never a 500 on the home page.

**Decision: Build the dashboard view-model in the resource, pass one object to the template.**
`HomeResource.home()` for an authenticated user assembles a `Dashboard` view-model: `pinned` (list),
`notifications` (list), `repositories` (full visible list). The full list keeps showing all visible
repos with each row reflecting pin state and offering the inverse action; pinned repos are not removed
from it (simpler, and the pinned section is a shortcut, not a filter). Rationale: keeps Qute templates
dumb and the assembly testable. Alternative: three separate template fragments fetching their own data
— spreads queries into the view layer.

**Decision: Deterministic ordering everywhere.** Pinned repos and the full list order by repository
name (reusing the existing `order by name` in `findVisibleTo`); notifications order by `(category,
title)`. Keeps rendering stable between identical requests (a spec requirement) and tests assertable.

## Risks / Trade-offs

- **Notifications section ships empty** → could read as "broken" to users. Mitigate with a clear empty
  state ("No notifications — assigned issues and merge requests will appear here once those features
  land") rather than a blank box.
- **`ON DELETE CASCADE` relies on DB enforcement** → H2 (tests) and the prod DB must both honor it;
  covered by a persistence test that deletes a pinned repo and asserts the pin is gone.
- **Pinned repos also appearing in the full list** → mild duplication. Accepted: the pinned section is
  a quick-access shortcut; hiding pinned rows from the full list would surprise users looking for "all".
- **CDI `Instance<NotificationSource>` iteration order is unspecified** → mitigated by sorting the
  aggregated result deterministically rather than depending on injection order.
- **Adding a Flyway migration (`V2`)** → forward-only; rollback = revert template/code and drop the
  `repository_pins` table. No existing data is modified.

## Migration Plan

Additive. Ship `V2__repository_pins.sql` (new table only), the new entity/service/endpoints, and the
restructured `home.html`. No backfill — every user starts with zero pins and an empty notifications
section. Rollback: revert the code change and drop the new table; no other data touched.

## Open Questions

- Single toggle endpoint vs. explicit `pin`/`unpin` — leaning explicit for idempotent, bookmarkable
  semantics, but a toggle is less markup. Decide at implementation.
- Whether to cap the notifications section height / paginate once real sources exist — out of scope now;
  revisit when the first source lands.
