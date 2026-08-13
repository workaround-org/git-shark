# The "Quick check" page

Some pages are expensive to build: a commit's diff, a page of history, a merge
request's changes, and search all read git objects live on every request. To keep
crawlers and bots from burning the instance's capacity, git-shark gives every
visitor a budget of those pages per minute. Go over it and you land on a **Quick
check** page (or, if your instance has no bot check configured, you get a plain
*Too many requests* response and can simply retry a moment later).

## What counts against the budget

Only these pages, and only when you open them:

- a commit's detail page (`…/commit/<id>`)
- a page of the **Commits** list (`…/commits/<branch>`)
- a merge request's page, which renders its diff
- the search results page

Everything else — repository overviews, branch and tag lists, issues, profiles,
your settings — is not metered. Neither is `git clone`, `git fetch` or `git push`:
git traffic and the REST API are not affected at all, so your tooling never sees a
check.

## Solving the check

The check is a Cloudflare Turnstile or hCaptcha widget, depending on what the
instance's admin configured. Most of the time it solves itself and you are sent
straight back to the page you wanted. Once solved, the confirmation lasts for a
while (30 minutes by default), so you are not asked again on every page.

The check needs JavaScript. If you have it turned off, log in instead.

## Logging in gives you a much bigger budget

Anonymous visitors share one budget per IP address; signed-in users are metered
per account with a far higher allowance (by default 600 pages per minute instead
of 60). If you keep hitting the check while browsing normally, **log in** — that
is the intended fix, not a workaround.

## If you keep seeing it

- Behind a shared or corporate IP address, other people's browsing counts against
  the same anonymous budget. Logging in gives you your own.
- If the check itself fails to load or keeps rejecting you, tell your instance
  admin: the widget keys may be misconfigured, or the provider unreachable from
  the server.
