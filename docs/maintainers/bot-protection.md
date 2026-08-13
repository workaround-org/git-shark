# Bot protection architecture

Metering for the expensive rendered pages plus an optional captcha challenge, all in
`de.workaround.protect`. No tables, no migrations, no scheduler: the whole subsystem
is a request filter, an in-memory counter map, an HMAC-signed cookie and one page.

## Component map

| Class | Role |
|---|---|
| `ProtectionConfig` | All `gitshark.protect.*` values; `captchaConfigured()` is the single "can we challenge?" predicate |
| `CaptchaProvider` | Enum of the four strings that differ between Turnstile and hCaptcha (script URL, widget CSS class, form field, `siteverify` URL) |
| `ExpensivePaths` | The metered path patterns; the only place that decides what counts as expensive |
| `RateLimiter` | Fixed-window counter map, keyed by caller |
| `ClientAddress` | Request-scoped client IP for the anonymous key |
| `HumanPass` | Mints and validates the signed `gitshark_human` pass |
| `ExpensiveRequestFilter` | `ContainerRequestFilter` that ties the above together |
| `CaptchaVerifier` | `siteverify` POST via `java.net.http`, fails closed |
| `ChallengeResource` | `GET`/`POST /challenge` plus the Qute page |

## Request flow

```
GET /repos/a/b/commit/<id>
  ExpensiveRequestFilter
    enabled? GET? ExpensivePaths.isExpensive(path)?      → no: pass through
    logged in            → key = "user:<principal>",      limit = userLimit
    valid gitshark_human → key = "pass:<cookie value>",   limit = userLimit
    otherwise            → key = "ip:<client-ip>",        limit = anonymousLimit
    limiter.tryAcquire(key, limit, window)
      → within budget:                            pass through
      → over budget, anonymous, captcha configured: 303 → /challenge?redirect=<original>
      → over budget, otherwise:                   429 + Retry-After
```

```
POST /challenge (redirect, <provider response field>)
  CaptchaVerifier.verify(token, clientIp)   → siteverify POST, {"success": bool}
    false → 403, re-render the page with an error
    true  → Set-Cookie gitshark_human=<expiry>.<hmac>; 303 → safeRedirect(redirect)
```

## Decisions

**Fixed windows, not sliding.** One map entry per caller (`start`, `count`) instead of
a timestamp list. The worst case — up to twice the budget across a window boundary —
does not matter for an abuse guard, and the memory profile stays flat. The map is
pruned of elapsed windows once it passes 50 000 keys so an IP spray cannot grow it
without bound.

**In memory, not in the database.** A limiter that writes rows would add database load
to the very requests it is meant to protect. Per-pod counters mean each replica
enforces its own share of the budget; a global limit belongs at the ingress, not here.

**Two budgets, three keys.** The threat is an unauthenticated crawler, so anonymous
callers are keyed by IP with a small budget and logged-in users by account with a
larger one. Keying logged-in users by account (not IP) also keeps a shared office IP
from punishing everyone once they sign in. A solved challenge is the third key: the
pass value is stable for its lifetime and unforgeable, so it identifies that visitor
on the larger budget even as their IP changes.

**A solved challenge raises the budget, it does not lift the limiter.** The pass used
to skip metering entirely, which made one captcha solve — a few tenths of a cent at a
solving farm, or one headless browser run — worth a full `pass-duration` of unmetered
scraping at whatever rate the server would answer. Metering the pass keeps the
challenge useful to a person (who never approaches the user budget) while capping what
a solve is worth to a crawler.

**No second challenge for a caller already on the raised budget.** Logged-in users and
pass holders get a plain `429` when they run out. Re-challenging them would either
loop forever (a logged-in user's pass changes nothing about their key) or hand out a
fresh budget per solve, which is the same bypass through a slower door.

**Only four paths, only GET.** `ExpensivePaths` deliberately excludes the git
transport, `/api/v1`, `runner.v1`, MCP and ActivityPub: those are machine callers with
their own auth that would break on a challenge (a clone cannot solve a captcha), and
they are already rate-limitable at the ingress. POSTs are excluded because a challenge
mid-form would discard the submitted body.

**Redirect to `/challenge` instead of rendering the challenge inside the filter.**
Rendering Qute from a `ContainerRequestFilter` risks blocking the event loop and
duplicates the page's model in two places. A `303` keeps `ChallengeResource` the only
renderer, and the original target rides along in `?redirect=` so the visitor lands
where they meant to.

**Signed cookie, no session store.** `<expiry-epoch-seconds>.<HMAC-SHA256>` keyed on
SHA-256 of the captcha secret key. That secret is necessarily present whenever
challenges can be issued, so the pass needs no extra configuration, and being
stateless it survives restarts and works across pods. Comparison uses
`MessageDigest.isEqual`. Reusing the captcha secret rather than `GITSHARK_SECRET_KEY`
keeps the feature independent of the mirror/CI secret setup.

**Nothing about the check is cacheable.** The `429`, the `303` towards `/challenge`
and the check page all carry `Cache-Control: no-store`. Only the `200` page would have
been at risk — `303` and `429` are not cacheable by default — but a response with no
cache directive at all is heuristically cacheable, and a proxy holding the page across
a provider switch or key rotation serves a widget that can no longer be solved.

**Fail closed on verification, fail open on configuration.** An unreachable or
unparseable `siteverify` reply is "not verified" — a broken provider must not become a
bypass. Conversely a missing/incomplete captcha config does not disable metering: it
only removes the challenge, and refusals become plain `429`s.

**Rate limiting on by default, captcha off.** Defaults (30/120 per minute) protect a
fresh instance immediately, while the captcha stays opt-in because it needs
third-party keys and sends visitor IPs to that provider. The numbers sit just above
fast human browsing rather than comfortably above it: a default nobody ever notices is
a default that stops nobody, and an admin who needs more can say so in one variable.

## What works today

- Fixed-window metering of commit, history, merge-request and search renderings, with
  separate anonymous (per-IP) and logged-in (per-account) budgets.
- `429` + `Retry-After` when no captcha is configured.
- Turnstile and hCaptcha challenges with server-side `siteverify`, both providers
  driven by the same code path.
- Signed, self-expiring pass cookie that moves its holder to the user budget for its
  lifetime, metered under its own key.
- Open-redirect-safe `?redirect=` handling (server-relative single-slash paths only).
- `Cache-Control: no-store` on every refusal, redirect and check-page response.
- Client IP taken from the proxy-aware remote address.

## What still needs to be implemented

- **Shared counters across replicas.** Each pod meters independently; a global budget
  would need a shared store (or ingress-level limiting).
- **A cost per solve.** Each solved challenge mints a fresh pass with a fresh budget,
  so a determined crawler can buy more throughput one solve at a time. Bounding it
  would need the pass to carry an identity worth tracking (a mint counter per IP, or a
  proof-of-work cost that rises with repetition).
- **Per-repository or per-path budgets.** One budget covers all metered paths; a repo
  with a huge history cannot be metered more tightly than a small one.
- **Configurable path set.** `ExpensivePaths` is compiled in; admins cannot add
  (say) tree or blob views without a code change.
- **Response caching.** The cheapest fix for expensive renderings is not to rebuild
  them — a rendered-diff cache keyed by commit id would reduce the need for metering
  in the first place.
- **Admin visibility.** No metrics or admin page for current counters, refusal counts
  or challenge solve rate.
- **Proof-of-work alternative.** A self-hosted challenge (Anubis-style) would avoid
  sending visitor IPs to a third party for admins who cannot use Turnstile/hCaptcha.
