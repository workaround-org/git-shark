# CI/CD runners

git-shark can run repository workflows on CI/CD **runners**. It speaks the Forgejo/Gitea runner
protocol, so the standard `forgejo-runner` (or Gitea `act_runner`) connects to it directly, and
workflows use the familiar GitHub-Actions-compatible YAML format in `.forgejo/workflows/`.

> **Available today:** an **instance administrator** registers runners against this instance, and a
> push that adds a workflow to `.forgejo/workflows/` (or `.gitea/workflows/`) starts a run that a
> connected runner picks up and executes, with logs and results shown on the repository's **Actions**
> tab. Triggers are limited to a plain `on: push` for now; richer triggers, secrets, and `needs`/
> `matrix` arrive in later phases.

## Running a workflow

1. An instance admin connects a runner (see the [admin guide](../admins/ci-runners.md)).
2. Add a workflow file such as `.forgejo/workflows/ci.yml` with a `push` trigger:

   ```yaml
   name: CI
   on: push
   jobs:
     build:
       runs-on: ubuntu-latest
       steps:
         - run: echo "hello from git-shark"
   ```
3. Push it. Each push to a branch creates a run (one per workflow file whose `on:` includes `push`).

## Viewing runs

Open the **Actions** tab on the repository. It lists every run — newest first — with its workflow
name, run number (`#1`, `#2`, …), status (Pending / Running / Success / Failure / Cancelled), the
triggering event and the short commit. Click a run to see each job and its log output (as of when
the page was loaded — reload to see newer lines).

A run whose runner disappears mid-job is marked **Failure** once its time limit passes (configurable
by the admin), so a run never hangs as Running forever.

## What's coming

- Richer triggers (branch/tag/path filters, tag pushes, merge-request events).
- Repository-level secrets and variables, `needs`/`matrix`, and run cancellation/re-run.
- Artifacts and commit/merge-request status integration.
