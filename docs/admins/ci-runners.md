# CI/CD runners

git-shark runs repository workflows on **external runners** rather than shipping its own runner
binary. It implements the server side of the Forgejo/Gitea runner protocol (`runner.v1`), so a
stock [`forgejo-runner`](https://forgejo.org/docs/latest/admin/actions/) or Gitea `act_runner`
registers and works against git-shark unchanged.

> **Phase 1 (this release):** runner **registration and presence** only — an admin generates a
> registration token, runners register and appear in the admin UI, and `Register`/`Declare`/`Ping`
> are served. **Workflow execution (fetching and running jobs) is not implemented yet** — that is a
> follow-up phase. Registering a runner today is useful for verifying connectivity and the token
> flow; it will not receive jobs until the run loop lands.

## Becoming an admin

There is no admin role in the database yet. An admin is any logged-in user whose handle is listed in
`GITSHARK_ADMIN_HANDLES` (comma-separated). Empty (the default) means the instance has no admins and
`/admin/*` is closed to everyone.

```yaml
    environment:
      GITSHARK_ADMIN_HANDLES: alice,bob
```

Admins get a **CI runners** entry in the Account menu, linking to `/admin/runners`.

## Registering a runner

1. As an admin, open **Account → CI runners** (`/admin/runners`) and click **Generate registration
   token**. The token is shown **once** — copy it.
2. On the runner host, register against this instance:

   ```
   forgejo-runner register --no-interactive \
     --instance https://gitshark.example.com \
     --token <registration token>
   ```

   `--instance` is the public origin of your git-shark deployment (the same URL users browse to);
   the runner reaches the protocol under `/api/actions`.
3. Start the runner (`forgejo-runner daemon`). It calls `Declare`, and appears in the admin UI with
   its version, labels, and last-seen time.

Registration tokens are **reusable** and **instance-scoped** (matching Gitea's global tokens): one
token can register any number of runners. Delete a token to stop it registering new runners; runners
already registered keep working (they authenticate with their own per-runner secret, not the
registration token). Repo/org-scoped and ephemeral runners are later phases.

## Endpoints

The runner protocol is Connect RPC (unary): a plain HTTP `POST` to
`/{package}.{Service}/{Method}` whose body is a serialized protobuf message and whose `200` response
body is the serialized response message (`Content-Type: application/proto`). All are served under
`/api/actions` and are **public** (no OIDC) — runners authenticate with their own credentials:

| Method | Path | Auth |
|---|---|---|
| `Ping` | `POST /api/actions/ping.v1.PingService/Ping` | none (health check) |
| `Register` | `POST /api/actions/runner.v1.RunnerService/Register` | registration token in request body |
| `Declare` | `POST /api/actions/runner.v1.RunnerService/Declare` | `x-runner-uuid` + `x-runner-token` headers |

`FetchTask`, `UpdateTask`, and `UpdateLog` exist in the protocol but are **not served yet** (phase 2+).

## Reverse-proxy requirements

Connect unary RPC is ordinary HTTP/1.1 `POST` with a binary body — no HTTP/2, no gRPC, no extra
port. Any proxy that already fronts git-shark works, provided it:

- forwards the `/api/actions/*` paths to the app unchanged;
- preserves the `application/proto` request/response bodies (do **not** let the proxy buffer,
  transcode, or gzip-rewrite them — pass through as-is);
- forwards the `x-runner-uuid` and `x-runner-token` request headers.

No new listener or TLS config beyond what [Getting Started](getting-started.md) already sets up.

## Security

- **Registration tokens and per-runner secrets** are stored only as SHA-256 hashes; each plaintext
  is shown exactly once at creation and never again (same model as personal access tokens).
- **Runner hosts are trusted infrastructure.** When the run loop and secrets delivery arrive, job
  secrets will be sent to whichever runner picks up a task; do not register runners you do not
  control. Fork-PR workflows with secrets are out of scope for now.
- Only handles in `GITSHARK_ADMIN_HANDLES` can generate tokens or see/delete runners.

## Tables

| Table | Contents |
|---|---|
| `ci_runner_registration_token` | Reusable registration tokens: `token_hash`, `created_by_id`, `created_at`, `last_used`. |
| `ci_runner` | Registered runners: `uuid` (the `x-runner-uuid` value), `token_hash`, `name`, `labels` (comma-joined), `version`, `status` (`IDLE`/`ACTIVE`/`OFFLINE`/`UNSPECIFIED`), `ephemeral`, `last_seen`, `created_at`. |

Both are introduced by migration `V19__ci_runners.sql`. They hold no repository data; losing them
only means runners must re-register.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| `register` fails with `401`/`unauthenticated` | Registration token wrong, or deleted in the admin UI. Generate a fresh one. |
| Runner registers but never runs anything | Expected in phase 1 — job execution is not implemented yet. |
| `Declare` returns `401` after a working `Register` | Proxy is stripping `x-runner-uuid` / `x-runner-token`; forward them. |
| Runner cannot reach the instance | `--instance` must be the public origin; the runner appends `/api/actions` itself. |
