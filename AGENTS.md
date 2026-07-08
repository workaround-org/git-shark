# Agent instructions for git-shark

git-shark is a self-hosted Git platform: a single Quarkus service (Java 21,
Panache Next, Qute server-rendered UI, PostgreSQL, Flyway) serving bare git
repositories over smart HTTP and SSH, with OIDC login and opt-in ForgeFed
federation. Source layout: `src/main/java/de/workaround/`, templates in
`src/main/resources/templates/`, migrations in
`src/main/resources/db/migration/`, docs in `docs/`.

## Keep the docs in sync

`docs/` is organized by audience. **Whenever you implement, change, or remove a
feature, update the matching docs in the same commit or PR** — do not leave doc
updates for later.

| You changed… | Update |
|---|---|
| Anything a logged-in user sees or does (UI pages, workflows, error messages, handles/URL forms) | `docs/users/` |
| Configuration properties, environment variables, exposed endpoints, deployment/proxy requirements, DB tables, operational behavior (queues, retries, monitoring) | `docs/admins/` — including the configuration-reference table in `admins/getting-started.md` when adding or changing a `GITSHARK_*`/config property |
| Architecture, implementation decisions, protocol behavior, or the capability set of a subsystem | `docs/maintainers/` |
| The overall feature set | The feature list in the root `README.md` |

Checklist per doc change:

- **New doc file** → add it to the index in `docs/README.md` under the right
  audience section, and link it from related docs.
- **Moved/renamed doc** → `grep -rn` the whole repo for the old path (root
  `README.md` links into `docs/`) and fix every reference.
- **Numbers and behavior claims** (defaults, limits, intervals, endpoint paths,
  table names) must match the source — verify against the code, don't copy
  from an older doc.

### Federation specifics

The federation subsystem has one doc per audience; a federation change usually
touches more than one:

- `docs/users/federation.md` — what users can do (follow/unfollow, feeds,
  identity, error messages).
- `docs/admins/federation.md` — config vars, allowlist semantics, exposed
  endpoints, reverse-proxy rules, delivery-queue operations, table inventory,
  troubleshooting.
- `docs/maintainers/forgefed.md` — component map, data flows, implementation
  decisions with rationale, and the **"What works today" / "What still needs
  to be implemented" lists: when you implement something from the gap list,
  move it to the works list; when you add a new capability or discover a new
  gap, record it.** New load-bearing design decisions belong in the decisions
  section with their why.

## Conventions

- Commit messages: `<gitmoji> (<context>): <message>`, e.g.
  `📝 (docs): Update federation user guide`.
- Never add functionality without a failing test first; the test must be green
  after the implementation. Docs-only changes need no tests.
- Code style: tabs, Allman braces; match the surrounding code.
