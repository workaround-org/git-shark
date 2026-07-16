# Federation: user guide

git-shark instances can talk to each other over [ForgeFed](https://forgefed.org)
(ActivityPub). For you as a user that means: you can **follow a public repository
that lives on another instance** and see its pushes on your own instance — no
account on the remote server needed.

> Federation is optional and off by default. If the features below are missing on
> your instance, the operator has not enabled it (see the
> [deployment guide](../admins/federation.md)).

---

## What you can do

- **Follow a remote user** and get all of their public repositories at once,
  grouped under that user, each with its own `Push` feed.
- **Follow a single remote public repository** and get its `Push` activity feed.
- **Unfollow** either again.
- **Be discovered**: your user and every public repository on your instance are
  visible to other ForgeFed servers.

What is *not* federated (yet): issues, merge requests, comments, forks, and
anything on private repositories. Private repositories are never exposed to
federation at all.

---

## Following a remote user

Open **Following** in the header navigation (you must be logged in) and use the
**Follow remote user** form with either a `username@host` handle or the user's
actor URL (`https://shark.example.com/ap/users/alice`). Your instance reads the
user's public repository list and follows each repository for you; they appear
grouped under the user, and their pushes flow into the **Recent pushes** feed
like any other follow.

Repositories the remote user creates *after* you follow them are picked up
automatically: your instance re-scans each followed user periodically (every few
minutes) and starts following any new public repository. Unfollowing the user
unfollows every repository that was fanned out from it.

---

## Following a remote repository

Open **Following** in the header navigation (you must be logged in), then enter
either form of address:

| Form | Example | When to use |
|---|---|---|
| Handle | `alice/demo@shark.example.com` | You know owner, name, and host |
| Actor URL | `https://shark.example.com/ap/repos/alice/demo` | You have a direct link |

The handle is resolved via WebFinger on the remote host; both resolve to the same
repository actor.

After submitting, the follow appears in your list as **Pending**: your instance
has sent a signed `Follow` and is waiting for the remote's `Accept`. Both
directions go through delivery queues that run every few seconds, so expect
**Pending → Accepted within roughly half a minute**. Refresh the page to see the
state change.

If it stays Pending for long, the remote is unreachable, not allowlisted by your
instance (or vice versa — federation requires **both** operators to allowlist each
other), or the repository doesn't exist / isn't public. Your operator can check
the delivery queue for the exact error.

### Common errors when following

| Message | Meaning |
|---|---|
| `Could not resolve handle` | WebFinger lookup failed: typo, host down, or host not on your instance's peer allowlist |
| `Could not resolve remote repository` | Actor URL didn't fetch: not a ForgeFed actor, private, or blocked by the allowlist |
| `Choose a username before following` | Your account hasn't finished onboarding — pick a username first |

---

## The "Recent pushes" feed

Once a follow is in place, pushes to the followed repository arrive as signed
`Push` activities and show up in the **Recent pushes** section of the Following
page: repository, ref, a summary like `Pushed 2 commit(s) to refs/heads/main`,
and when it was received. The feed shows the newest 50 entries across everything
you follow.

Only pushes from repositories somebody on your instance follows are stored;
everything else is dropped on arrival.

---

## Unfollowing

Hit **Unfollow** next to the entry. Your instance sends an `Undo(Follow)` to the
remote so it stops delivering, and the entry disappears from your list.

---

## Your federated identity

When federation is enabled, you exist to other servers as an ActivityPub `Person`:

- Actor: `https://<your-host>/ap/users/<username>`
- WebFinger: `acct:<username>@<your-host>`

Each public repository is a ForgeFed `Repository` actor
(`https://<your-host>/ap/repos/<owner>/<name>`, WebFinger
`acct:<owner>/<name>@<your-host>`). Remote users can follow your public
repositories the same way you follow theirs; their servers receive your pushes
automatically. Followers of a repository are public at
`…/ap/repos/<owner>/<name>/followers`.

Signing keys for your actors are generated and managed by the server — there is
nothing for you to configure.

Remote federated users and repositories are identified by their remote handle
only — unlike local accounts, they don't carry a profile picture (see
[Profile settings](profile.md)).
