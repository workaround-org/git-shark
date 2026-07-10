# Repository visibility

Every repository is either **public** or **private**. You choose the visibility
when creating the repository, and the owner can change it later at any time.

## What visibility means

- **Public** — anyone can see, browse, and clone the repository, without an
  account. If the instance has [federation](federation.md) enabled, the
  repository is also discoverable and followable from other ForgeFed servers.
- **Private** — the repository is visible only to you, your
  [collaborators](collaborators.md), and (for organisation repositories)
  organisation members. To everyone else it does not exist: they get a
  404, not a permission error.

## Changing the visibility

1. Open the repository and go to **Settings** in the sidebar (owner only).
2. In the **Visibility** section, pick *Public* or *Private* and click
   **Change visibility**.

The change takes effect immediately:

- Making a repository **public** exposes its entire contents and history to
  everyone — including anything ever committed, such as secrets in old
  commits. Check the history before opening a repository up.
- Making a repository **private** hides it right away from anonymous
  visitors, search, `/explore`, and federation. Remote instances that were
  following the repository stop receiving pushes immediately, but they are
  not actively notified and may still list the follow on their side.

Only the repository owner (or an organisation **owner** for org repositories)
can change visibility.
