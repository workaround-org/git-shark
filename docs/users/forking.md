# Forking a repository

A **fork** is your own copy of someone else's repository, living under your
namespace. Fork a project to experiment freely, then push your branch and open a
merge request back to the original.

## How to fork

Open any repository you can see and click the **fork** button (⑂) in the
repository sidebar, next to Clone and Pin. git-shark creates a new repository at
`/<your-handle>/<name>` and sends you straight to it.

The fork starts as a faithful copy of the source at that moment:

- the same **name**, **description**, and **visibility** as the source;
- **every branch and tag**, with the same default branch;
- a **“forked from `owner/name`”** link in the sidebar pointing back at the
  original.

You own the fork outright — push, rename, change its visibility, or delete it
without touching the original. A fork does **not** stay in sync with its source
automatically; pulling later changes from upstream is a manual `git` operation
for now.

## What you can fork

You can fork any repository you are allowed to read: every public repository,
plus private ones you own or that are shared with you (as a collaborator or
through an organisation). A private repository can never be forked by someone
who cannot already read it, so forking never exposes a private project.

If you fork a **private** repository, your fork is created **private** too.

## If you already have a fork

You can only have one repository of a given name in your namespace. If you
already forked a project (or own a repository with that name), the fork button
simply takes you to that existing repository instead of creating a duplicate.

## Forking from the API or an AI client

- REST: `POST /api/v1/repos/{owner}/{name}/fork` with your access token — see the
  [admin reference](../admins/forking.md).
- MCP: the `forkRepository` tool, listed in the [AI clients guide](mcp.md).
