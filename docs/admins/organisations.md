# Organisations (admin notes)

Organisations need **no configuration** — the feature is always on and purely
database-backed. This page documents the semantics and the tables involved.

## Semantics

- Organisation names live in the **same handle namespace as usernames**. Creating an
  organisation checks `users.username` and `organisations.name`; choosing or renaming a
  username checks `organisations.name` too. Each table also has its own unique index,
  but the cross-table check is application-level and runs inside the creating
  transaction.
- A repository is owned by **exactly one** user or organisation
  (`repositories.owner_user_id` XOR `repositories.owner_org_id`, DB CHECK
  `repositories_exactly_one_owner`).
- The `{owner}` segment of every repo route (`/repos/{owner}/{name}`, smart HTTP
  `/git/{owner}/{name}.git`, SSH path) resolves to a user first, then an organisation.
- Authorization flows through the same single access policy as everything else:
  org **guest** = read (including private org repos), **member** = read+write,
  **owner** = admin (repo delete/settings/collaborators/mirrors, member management,
  org deletion). The last owner of an organisation cannot be removed or downgraded.
- Deleting an organisation is blocked while it still owns repositories.
- On-disk storage is unchanged: bare repositories live under
  `<storage-root>/<owner-uuid>/<repo-uuid>.git`, where the owner UUID is the user's or
  the organisation's id.

## Endpoints

| Route | Access |
|---|---|
| `GET /orgs/new`, `POST /orgs` | any logged-in user |
| `GET /orgs/{name}` | public (repo list filtered by viewer visibility) |
| `GET/POST /orgs/{name}/members`, `POST /orgs/{name}/members/{username}/role`, `POST /orgs/{name}/members/{username}/remove` | org owners only |
| `POST /orgs/{name}/delete` | org owners only |

## Tables

Added by migration `V14__organisations.sql`:

| Table | Purpose |
|---|---|
| `organisations` | id, unique `name` (shared handle namespace), optional `display_name`, `created_at` |
| `organisation_members` | (organisation, user, role ∈ GUEST/MEMBER/OWNER), unique per (organisation, user), cascade-deleted with either side |

The same migration makes `repositories.owner_id` nullable as `owner_user_id`, adds
`owner_org_id`, a CHECK that exactly one is set, and a unique index on
`(owner_org_id, name)`.

## Federation

Organisations have no ActivityPub actor and no WebFinger entry yet. Because org names
are reserved in the shared handle namespace, enabling org actors later cannot collide
with user actors. Nothing to configure or proxy for orgs today.
