## 1. Data model + migration

- [x] 1.1 Add `V4__username_nullable.sql` dropping NOT NULL on `users.username` (keep unique)
- [x] 1.2 Document in `model/User.java` that a null/blank `username` means onboarding-pending

## 2. Handle utilities (test-first)

- [x] 2.1 Write `HandleSuggesterTest` (SPN strip, lowercase, invalid-char sanitize, clamp)
- [x] 2.2 Implement `account/HandleSuggester.suggest(String claim)`
- [x] 2.3 Write `UsernameValidationTest` (pattern, length, uniqueness collision)
- [x] 2.4 Implement `account/UsernameService.choose(User, String handle)` + `InvalidUsernameException` / `UsernameTakenException`

## 3. Provisioning

- [x] 3.1 Write `UserProvisioningOnboardingTest` (provisionFromOidc creates null handle; dev adopt keeps handle)
- [x] 3.2 Add `UserProvisioningService.provisionFromOidc(sub, claimUsername, displayName, email)`
- [x] 3.3 Wire `CurrentUser` JWT branch to `provisionFromOidc`; leave non-JWT branch unchanged

## 4. Onboarding page + guard

- [x] 4.1 Write `OnboardingRedirectTest` (handle-less user 303 → /onboarding from / and /settings/keys; onboarded user not redirected)
- [x] 4.2 Implement `account/OnboardingFilter` (ContainerRequestFilter redirect guard)
- [x] 4.3 Write onboarding flow test (GET suggestion render; POST invalid/taken → error; POST valid → 303 /; revisit → 303 /)
- [x] 4.4 Implement `account/OnboardingResource` (GET/POST) + `templates/HomeResource/onboarding.html`
- [x] 4.5 Add `/onboarding` to `quarkus.http.auth.permission.authenticated.paths`

## 5. Settings profile (rename + display name)

- [x] 5.1 Write `SettingsProfileTest` (rename valid; collision rejected; display-name edit)
- [x] 5.2 Add `GET`/`POST /settings/profile` to `SettingsResource` reusing `choose(...)` + `templates/SettingsResource/profile.html` + nav link

## 6. Verify

- [x] 6.1 Run full test suite green; format (`mvn formatter:format` + `mvn impsort:sort`)
- [ ] 6.2 Manual dev check: fresh OIDC subject → onboarding → handle in repo/AP/webfinger URLs; rename via settings; seeded alice unaffected
