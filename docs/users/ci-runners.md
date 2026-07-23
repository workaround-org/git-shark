# CI/CD runners

git-shark can run repository workflows on CI/CD **runners**. It speaks the Forgejo/Gitea runner
protocol, so the standard `forgejo-runner` (or Gitea `act_runner`) connects to it directly, and
workflows use the familiar GitHub-Actions-compatible YAML format in `.forgejo/workflows/`.

> **Available today:** an **instance administrator** registers runners against this instance, and a
> push that adds a workflow to `.forgejo/workflows/` (or `.gitea/workflows/`) starts a run that a
> connected runner picks up and executes, with logs and results shown on the repository's **Actions**
> tab. Runs are triggered by `push` (with branch/tag/path filters) and jobs can be ordered with
> `needs` (results and outputs included); other events and `matrix` arrive in later phases.

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

Users with write access see controls on the run page: **Cancel run** (while it is still running —
settles the run and tells the active runner to stop) and **Re-run** (on a finished run — resets its
jobs and runs them again).

Pushing a new commit to a branch automatically cancels that branch's earlier still-running run, so
only the latest push keeps running.

## Trigger filters

Beyond a bare `on: push` (which runs on every branch push), you can scope runs to specific refs:

```yaml
on:
  push:
    branches: [main, 'release/*']   # only these branches (globs: * within a segment, ** across)
    tags: ['v*']                    # and pushes of matching tags
    paths: ['src/**']               # only when a changed file matches
```

Use `branches-ignore` / `tags-ignore` / `paths-ignore` to invert. A block with only `tags:` runs on
tag pushes and not on branch pushes. `paths` runs when any changed file matches; `paths-ignore` runs
unless every changed file is ignored. Non-push events are not evaluated yet.

A job runs only on a runner that advertises every label in its `runs-on` (e.g. `runs-on: ubuntu-latest`
needs a runner registered with the `ubuntu-latest` label); a job with no `runs-on` runs on any runner.

## Secrets and variables

Repository owners manage CI secrets and variables under **Settings → CI secrets & variables**. Both
are delivered to the runner executing the repo's workflows and are available in the usual contexts —
`${{ secrets.NAME }}` and `${{ vars.NAME }}`.

- **Secrets** are encrypted at rest and **write-only**: once saved, the value is never shown again
  (you can only replace it by deleting and re-adding). Storing secrets requires the instance to have
  an encryption key configured.
- **Variables** are plain configuration and their values are visible on the settings page.

## Job ordering with `needs`

A job can depend on others with `needs`. A dependent job runs only after every job it needs has
succeeded; if one fails, the dependent (and anything downstream of it) is cancelled.

```yaml
jobs:
  build:
    runs-on: ubuntu-latest
    steps: [{ run: make }]
  deploy:
    needs: build
    runs-on: ubuntu-latest
    steps: [{ run: make deploy }]
```

The result **and outputs** of each needed job are available to dependents — set outputs in the
upstream job and read them with `${{ needs.build.outputs.* }}`.

## Matrix builds

A job with `strategy.matrix` runs once per combination of its values, each a separate entry on the
Actions page:

```yaml
jobs:
  test:
    runs-on: ubuntu-latest
    strategy:
      matrix:
        os: [linux, windows]
        jdk: [17, 21]
    steps:
      - run: echo "${{ matrix.os }} / ${{ matrix.jdk }}"
```

This produces four runs (`test (linux, 17)`, `test (linux, 21)`, …). A job that `needs` a matrix job
waits for all of its cells. `matrix.include` / `matrix.exclude` are not supported yet.

## What's coming

- Non-push events (`pull_request`, scheduled, manual).
- Artifacts and commit/merge-request status integration.
