# CI/CD runners

git-shark can run repository workflows on CI/CD **runners**. It speaks the Forgejo/Gitea runner
protocol, so the standard `forgejo-runner` (or Gitea `act_runner`) connects to it directly, and
workflows use the familiar GitHub-Actions-compatible YAML format in `.forgejo/workflows/`.

> **Available today:** an **instance administrator** can register runners against this instance and
> see them listed. **Running workflows is not enabled yet** — pushing a workflow file does not yet
> start a job. This page will grow as workflow execution, logs, and per-repository run views land.

## What you can do now

- **If you are an instance admin**, you manage runners under **Account → CI runners**. See the
  [admin guide](../admins/ci-runners.md) for generating a registration token and connecting a runner.
- **If you are not an admin**, there is nothing to configure yet. Runner management is
  instance-wide and admin-only in this phase; per-repository controls and workflow authoring arrive
  in later phases.

## What's coming

- Workflows in `.forgejo/workflows/*.yml` triggered on push, with live per-step logs and results in
  the repository UI.
- Repository-level secrets and variables, `needs`/`matrix`, and run cancellation/re-run.
- Artifacts and commit/merge-request status integration.
