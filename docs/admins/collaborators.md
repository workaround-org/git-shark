# Operating collaborators

Collaborators let a repository owner grant other **local** users read+write access to a
repository (one flat role). This page covers what an operator must know: there is no
configuration, one new table, and no new proxy rules.

## Configuration

None. The feature is always on and needs no environment variables.

## Endpoints

Collaborator management happens on existing web UI paths — no new reverse-proxy rules:

- `GET /repos/{owner}/{name}/settings/collaborators` — the settings page (owner-only;
  non-owners get `403`, users who cannot see the repository get `404`)
- `POST /repos/{owner}/{name}/settings/collaborators` — add by username
- `POST /repos/{owner}/{name}/settings/collaborators/{username}/remove` — remove

## Access semantics

- A collaborator gets **read and write** on the repository over every path — web UI, git
  over HTTP, git over SSH, REST API, and MCP — including read access to **private**
  repositories.
- Collaborators can manage issues and merge requests like the owner.
- Deleting the repository, managing push mirrors, and managing collaborators stay
  **owner-only**.
- Removing a collaborator takes effect on the next request; there is no session to
  invalidate.

## Tables

| Table | Contents |
|---|---|
| `repository_collaborators` | One row per grant: `repository_id`, `user_id`, `created_at`; unique per (repository, user), rows cascade-deleted with the repository or the user |

Added by migration `V12__repository_collaborators.sql`.
