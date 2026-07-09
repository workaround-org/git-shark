# Collaborators

A collaborator is another user on your git-shark instance whom you grant **read and
write** access to one of your repositories. There is one flat collaborator role — no
permission levels, no teams. Adding a collaborator takes effect immediately; there is
no invitation to accept.

Only the **repository owner** can see and manage a repository's collaborators.

## What a collaborator can do

- Read the repository — browse it in the web UI and clone/fetch it over HTTP and SSH,
  including when the repository is **private**.
- Push over HTTP and SSH.
- Open and manage issues and merge requests, and moderate merge-request review comments,
  just like the owner.

A collaborator can **not**:

- Delete the repository.
- Manage push mirrors.
- Add or remove collaborators — granting access stays with the owner.

## Adding a collaborator

1. Open your repository's **Settings** page (the ⚙ Settings tab in the left sidebar,
   owner only) and click **Manage collaborators** (it opens `…/settings/collaborators`).
2. Enter the user's exact **username** (the handle used in their profile URLs — there is
   no search or autocomplete) and click **Add**.

The page tells you when the username doesn't exist, the user is already a collaborator,
or you tried to add yourself.

Only local users can be added — collaborators from other federated instances are not
supported.

## Removing a collaborator

On the same page, click **Remove** next to the collaborator. Their read and write access
ends immediately; on a private repository they lose all access.
