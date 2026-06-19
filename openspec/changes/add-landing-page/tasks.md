## 1. Failing tests first

- [x] 1.1 Add a test: anonymous `GET /` returns 200 and body contains all three value propositions ("Use AI as a tool, not as a feature", "Focus on co-working, not lazy feeds", "Easy and painless to self-host")
- [x] 1.2 Add a test: anonymous `GET /` body does NOT render the repository table (asserts landing, not repo list)
- [x] 1.3 Add a test: authenticated `GET /` still renders the repository list (existing behavior unchanged)
- [x] 1.4 Add a test: anonymous `GET /explore` returns 200 and lists repositories visible to anonymous users
- [x] 1.5 Run the suite, confirm the new tests fail (red)

## 2. Backend routing

- [x] 2.1 Add `landing()` native template method to `HomeResource.Templates` (`@CheckedTemplate`)
- [x] 2.2 Branch `HomeResource.home()` on `currentUser.get()`: null → `Templates.landing(...)`, non-null → existing repo list
- [x] 2.3 Add `GET /explore` endpoint rendering the repository list for `service.listVisibleTo(null)`
- [x] 2.4 Add protected `GET /login` (in `authenticated` paths) that OIDC intercepts, then redirects to `/`

## 3. Templates & styling

- [x] 3.1 Create `templates/HomeResource/landing.html`: dark hero, glowing ASCII-art `<pre>` block, three value props, "Log in" CTA, self-host link, "Browse public repositories" → `/explore`
- [x] 3.2 Add CSS for the dark hero + glow (stacked `text-shadow`, monospace `<pre>`, `clamp()` font size, `overflow-x:auto` wrapper); no JavaScript
- [x] 3.3 Landing renders a self-contained anonymous header (Log in / self-host) instead of guarding `layout.html` nav — avoids hiding the authenticated nav on repo pages that don't pass `user`

## 4. Verify

- [x] 4.1 Run `./mvnw test` — all tests green (52/52)
- [ ] 4.2 Manual check in `quarkus:dev`: anonymous `/` shows hero, logged-in `/` shows repos, `/explore` lists public repos
- [ ] 4.3 Update `README.md` if the public landing/`/explore` behavior is user-facing
