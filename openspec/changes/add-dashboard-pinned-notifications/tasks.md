## 1. Failing tests first

- [x] 1.1 Persistence test: pinning a repo creates a `RepositoryPin`; pinning twice is idempotent (no duplicate); unpinning removes it
- [x] 1.2 Persistence test: deleting a pinned repository removes its pins (cascade); pins are isolated per user (user A's pin not seen for user B)
- [x] 1.3 Service test: `NotificationService` with zero registered sources returns an empty list; with test `NotificationSource` instances, returns their items; ordering is deterministic
- [x] 1.4 Service test: when one `NotificationSource` throws, aggregation still returns items from the other sources and does not propagate the error
- [x] 1.5 Web test: authenticated `GET /` renders three sections — pinned, notifications (empty state), all repositories
- [x] 1.6 Web test: `POST /repos/{owner}/{name}/pin` adds the repo to the user's pinned section on the next `GET /`; `unpin` removes it
- [x] 1.7 Web test: pinning a non-visible repo is rejected (404); anonymous pin is unauthorized (403); anonymous `GET /` still serves the landing page (unchanged)
- [x] 1.8 Run the suite, confirm the new tests fail (red) — failed at compile against not-yet-written production code

## 2. Persistence — repository pinning

- [x] 2.1 Add `RepositoryPin` entity (`user`, `repository`, `createdAt`) with a unique constraint on `(user, repository)` and a Panache `Repo` interface (findByUserAndRepository, findByUser, findPinnedRepositories)
- [x] 2.2 Add Flyway migration `db/migration/V2__repository_pins.sql`: table with FKs to `users` and `repositories`, both `ON DELETE CASCADE`, unique `(user_id, repository_id)`
- [x] 2.3 Add a `RepositoryPinService`: `pin(user, repo)`, `unpin(user, repo)`, `listPinned(user)` ordered by name, `isPinned(user, repo)`, enforcing visibility via `AccessPolicy`

## 3. Notification framework

- [x] 3.1 Define `NotificationItem` (record: category, title, optional repository, targetUrl) and `NotificationSource` interface (`List<NotificationItem> notificationsFor(User user)`)
- [x] 3.2 Implement `NotificationService` injecting `Instance<NotificationSource>`, aggregating per-source results, isolating failures (try/catch + log), and sorting deterministically by `(category, title)`
- [x] 3.3 Confirm zero concrete sources are wired (empty `Instance` → empty list); no Issue/MR code added in this change

## 4. Dashboard routing & view-model

- [x] 4.1 Build a dashboard view-model (pinned, notifications, `DashboardRepo` rows) and have `HomeResource.home()` assemble it for authenticated users; anonymous still routes to the landing page
- [x] 4.2 Add `POST /repos/{owner}/{name}/pin` and `POST .../unpin` endpoints: `currentUser.require()`, visibility check, then `seeOther` back to the (validated) referrer (default `/`). Added a `ForbiddenOperationException`→403 mapper so anonymous mutations return 403 instead of 500
- [x] 4.3 Add the `dashboard(...)` native template method to `@CheckedTemplate`

## 5. Templates & styling

- [x] 5.1 Add `templates/HomeResource/dashboard.html` with three sections: Pinned (with empty state), Notifications (empty-state copy referencing future issues/MRs), All repositories. Kept `home.html` as the plain list for the anonymous `/explore` route so the dashboard sections don't leak there
- [x] 5.2 Add a pin/unpin `<form>` control to each repo row reflecting current pin state (Pin if unpinned, Unpin if pinned)
- [x] 5.3 Add a pin/unpin control to `RepositoryResource/overview.html`
- [x] 5.4 Add CSS in `shark.css` for the dashboard sections, pin controls, and empty states; no JavaScript

## 6. Verify

- [x] 6.1 Run `./mvnw test` — 69/70 green. The one failure (`SettingsPagesTest.anonymousIsRedirectedToLogin`) is a pre-existing Keycloak Dev Service startup flake ("OIDC Server is not available"), unrelated to this change; all new tests pass
- [ ] 6.2 Manual check in `quarkus:dev`: pin/unpin reflects in the pinned section; notifications shows the empty state; full list still lists all visible repos; anonymous `/` unchanged
- [x] 6.3 Update `README.md` for the user-facing dashboard/pinning behavior and the new `repository_pins` table
