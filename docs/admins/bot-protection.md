# Bot protection and rate limits

Expensive rendered pages — per-commit diffs, history pages, merge-request diffs and
search — are metered per caller, and an optional captcha lets a refused human
continue. Rate limiting is **on by default**; the captcha is **off by default** and
needs keys from Cloudflare Turnstile or hCaptcha.

Nothing is persisted: counters and the "already solved" pass live in memory and in a
signed cookie, so there are no new tables and no migrations.

## What is metered

| Path | Why |
|---|---|
| `GET /repos/{owner}/{name}/commit/{id}` | parses the commit and builds a full tree-to-tree diff |
| `GET /repos/{owner}/{name}/commits[/{ref}]` | revwalk over the ref |
| `GET /repos/{owner}/{name}/merge-requests/{number}` | renders the branch diff |
| `GET /search` | repository + people search across the instance |

Only `GET` is metered. Everything else is untouched — notably the git smart-HTTP
transport, `/api/v1`, the `runner.v1` CI endpoints, the MCP server and the
ActivityPub/ForgeFed endpoints. Those authenticate their own callers and are driven
by tools that cannot solve a challenge, so a limit there would break clones,
Renovate and federation rather than stop a crawler.

## Budgets

Each caller gets a fixed window (default 1 minute) and a budget inside it:

| Caller | Key | Default budget |
|---|---|---|
| Anonymous | client IP | 30 per window |
| Anonymous, passed the check | the pass itself | 120 per window |
| Logged in | user account | 120 per window |

The defaults are deliberately close to what a person browsing quickly can produce and
far below what a scraper wants: 30 per minute is one expensive page every two seconds,
sustained. Raise them if your instance serves an audience that genuinely browses
faster — but raise them knowingly, because the default that never annoys anyone also
never stops anyone.

The client IP is read from the Vert.x remote address, which already honours
`X-Forwarded-For` because `quarkus.http.proxy.allow-x-forwarded` is enabled. Make
sure your reverse proxy sets `X-Forwarded-For` — without it every visitor behind
the proxy shares one anonymous budget.

Counters are **per instance and not replicated**. With several replicas each pod
enforces its own share of the budget, which is enough to stop a crawler hammering
one node; if you need a global limit, do it at the ingress.

## Over budget

- **No captcha configured** → `429 Too Many Requests` with a `Retry-After` header
  set to the window length, and a short plain-text body.
- **Captcha configured, anonymous visitor** → `303 See Other` to
  `/challenge?redirect=<original path>`. Solving the widget mints a signed pass
  cookie (`gitshark_human`, `HttpOnly`, `SameSite=Lax`, `Secure` over HTTPS) that
  raises the visitor to the **user budget** for `pass-duration`, metered against the
  pass itself rather than the client IP.
- **Already on the raised budget** (logged in, or carrying a pass) → plain `429`. A
  second challenge would only hand out a fresh budget, and challenging a caller who
  cannot improve their standing loops forever.

A solved check raises the budget; it does not remove it. A bypass would turn one
captcha solve — a few tenths of a cent at a solving farm — into a window of entirely
unmetered scraping, which is precisely the traffic the guard exists to stop.

The pass is `<expiry-epoch-seconds>.<HMAC-SHA256>`, signed with a key derived from
the captcha secret key — no server-side session state, so it survives restarts and
works across pods. A forged, re-signed or expired value is simply ignored.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `GITSHARK_PROTECT_ENABLED` | `true` | Master switch for metering |
| `GITSHARK_PROTECT_ANONYMOUS_LIMIT` | `30` | Expensive pages per window, per client IP |
| `GITSHARK_PROTECT_USER_LIMIT` | `120` | Expensive pages per window, per logged-in account — and per solved check |
| `GITSHARK_PROTECT_WINDOW` | `1m` | Window length |
| `GITSHARK_PROTECT_CAPTCHA_PROVIDER` | `none` | `none`, `turnstile` or `hcaptcha` |
| `GITSHARK_PROTECT_CAPTCHA_SITE_KEY` | — | Public widget key |
| `GITSHARK_PROTECT_CAPTCHA_SECRET_KEY` | — | Server-side key; also signs the pass cookie |
| `GITSHARK_PROTECT_CAPTCHA_VERIFY_URL` | — | Override the provider's `siteverify` endpoint (testing) |
| `GITSHARK_PROTECT_CAPTCHA_PASS_DURATION` | `15m` | How long a solved check keeps the visitor on the user budget |

An unknown provider value is treated as `none`, and a provider **without both keys**
also counts as no captcha: `/challenge` answers `404` and refusals stay plain
`429`s. Rate limiting keeps working either way — the guard never depends on a
third-party widget being reachable to be able to say no.

### Turnstile example

```yaml
    environment:
      GITSHARK_PROTECT_CAPTCHA_PROVIDER: turnstile
      GITSHARK_PROTECT_CAPTCHA_SITE_KEY: 0x4AAA...
      GITSHARK_PROTECT_CAPTCHA_SECRET_KEY: 0x4AAA...
      GITSHARK_PROTECT_ANONYMOUS_LIMIT: "20"
```

With a challenge available the anonymous budget can be tighter than the default: a
refused human is one click away from continuing, so `20` costs a visitor almost
nothing while halving what a crawler gets for free.

Register the site key for your instance's hostname in the Cloudflare dashboard
(hCaptcha: in the hCaptcha dashboard) and keep the secret key out of the image —
pass it via the environment or a secret, like `GITSHARK_SECRET_KEY`.

### Outbound network

Token verification is a server-side `POST` from git-shark to the provider:

- Turnstile: `https://challenges.cloudflare.com/turnstile/v0/siteverify`
- hCaptcha: `https://api.hcaptcha.com/siteverify`

Allow egress to that host, or challenges can never be solved. Verification fails
**closed**: a timeout, a non-`200` or an unparseable body means "not verified", and
the visitor is re-shown the check with an error. Connect timeout 5 s, request
timeout 10 s.

Browsers additionally load the widget script from
`https://challenges.cloudflare.com` / `https://js.hcaptcha.com`. If you serve a
Content-Security-Policy at the proxy, allow those hosts in `script-src` and
`frame-src`.

## Endpoints

| Method & path | Auth | Purpose |
|---|---|---|
| `GET /challenge?redirect=<path>` | None | The check page (404 unless a captcha is fully configured) |
| `POST /challenge` | None | Verifies the token, sets the pass cookie, redirects to `redirect` |

`redirect` is only honoured when it is a single-slash server-relative path;
anything else falls back to `/`, so the challenge cannot be turned into an open
redirect.

## Tuning and troubleshooting

| Symptom | Cause / fix |
|---|---|
| Legitimate users hit the check while browsing | Raise `GITSHARK_PROTECT_ANONYMOUS_LIMIT`, or tell users to log in (`USER_LIMIT` applies then). Check that the proxy forwards `X-Forwarded-For` — otherwise all visitors share one budget. |
| Everyone is challenged at once, from one IP | The proxy is not forwarding the real client IP (see above). |
| `/challenge` returns 404 | No provider selected, or one of the two keys is missing. |
| Check always rejects the token | Wrong secret key, site key not registered for this hostname, or the server cannot reach `siteverify` (look for `captcha siteverify failed` / `returned HTTP …` in the logs). |
| A visitor is refused right after solving the check | Expected above `USER_LIMIT`: the pass raises the budget, it does not remove it. Genuine browsing does not reach 120 expensive pages a minute — raise `GITSHARK_PROTECT_USER_LIMIT` only if it does. |
| Crawler still hammers one endpoint | Only the four paths above are metered by design; block the rest at the ingress. |
| Want no metering at all | `GITSHARK_PROTECT_ENABLED=false`. |

Nothing needs a restart other than the usual config reload: all values are read at
startup, so change the environment and recreate the container.
