# Organisations

An organisation is a shared namespace for repositories, owned and used by a group of
members instead of a single user. An organisation owns repositories exactly like a user
does — an org repository lives at `/repos/<org>/<repo>` and is cloned via the same HTTP
and SSH URL forms as a personal one.

Organisation names share **one namespace with usernames**: you cannot create an
organisation named like an existing user, and nobody can pick a username matching an
existing organisation.

## Creating an organisation

1. On your dashboard (`/`), click **New organisation** (next to "New repository").
2. Pick a **name** — same rules as usernames: 1–39 characters, lowercase letters,
   digits or hyphens, starting with a letter or digit, unique across users *and*
   organisations. Optionally add a display name.

You become the organisation's first **owner**.

Every organisation you belong to (any role) is listed in an **Organisations** section on
your dashboard (`/`), linking to its page at `/orgs/<name>`.

## Member levels

| Level | Rights |
|---|---|
| **guest** | Read-only: sees the org's private repositories in the UI and can clone/fetch them over HTTP and SSH, but cannot push |
| **member** | Read + write: everything a guest can, plus push over HTTP and SSH |
| **owner** | Admin: manage members and their roles, create and delete org repositories, manage their settings/collaborators/mirrors, delete the organisation |

Public org repositories are world-readable like any public repository.

## Managing members

Open the organisation page at `/orgs/<name>` and click **Members** (owners only).

- **Add**: enter the exact username of a local user and pick a role. Adding takes
  effect immediately — there is no invitation to accept. The page tells you when the
  username doesn't exist or the user is already a member.
- **Change role**: pick a new role in the member's row.
- **Remove**: click **Remove** next to the member; their access ends immediately.

An organisation must always keep at least one owner: the last owner cannot be removed
or downgraded.

## Repositories in an organisation

When creating a repository (**New repository**), an **Owner** selector appears if you
own any organisations — choose yourself or one of them. Only organisation **owners**
can create repositories in an org.

Org repositories behave like personal ones everywhere else: issues, merge requests,
collaborators, mirrors, pinning, custom repository image. Administrative actions on
them (settings, delete, collaborators, mirrors) are available to every org owner.

## Deleting an organisation

On the Members page, under **Danger zone**, type the organisation name to confirm.
An organisation that still owns repositories cannot be deleted — delete its
repositories first.

## Federation

Organisations are not federated actors yet: an org has no ActivityPub identity and no
WebFinger entry. The name is still reserved instance-wide, so org actor support can be
added later without collisions.
