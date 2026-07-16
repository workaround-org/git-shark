# Federated collaboration: roadmap

Forward-looking plan for growing federation from **follow-and-feed** (what ships
today) into **cross-instance collaboration** — discovering people's work on other
instances, forking it, and contributing back with a merge request that travels
over ForgeFed.

This document is the *why* and the *sequence*. For how the current subsystem is
built see [ForgeFed architecture](forgefed.md); for operating it see the
[deployment guide](../admins/federation.md); for the user view see the
[user guide](../users/federation.md).

---

## The vision

A contributor on instance A wants to help with a project on instance B without
opening an account on B:

1. **Follow the author** on B and see all their public repositories aggregated
   in one place, with recent activity.
2. **Click through** to a repository's activity and open it.
3. **Fork** it onto instance A (their home instance).
4. Push a contribution branch to the fork and **open a merge request** back
   against the upstream on B — over federation, no account on B.
5. See the outcome (accepted / rejected / merged) back on A.

Steps 1–3 are mostly discovery and plumbing that build on existing pieces.
Step 4 is the protocol epic.

---

## Where we start

Today federation is **repository-follow + push feed** (see the "What works today"
list in [forgefed.md](forgefed.md)). You follow *repositories*, not people; there
is no cross-instance fork and no federated merge request. Local issues, MRs, and
forks do not cross the instance boundary.

---

## Stories

### Story 1 — Follow a user, see their aggregated repositories *(done)*

Follow a remote `Person` actor and see their public repositories, grouped, with
recent push activity per repo — instead of following each repository one by one.

- ✅ `Person` actor exposes a `repositories` collection (`/ap/users/{username}/repositories`,
  public repos only) and advertises it in the actor document.
- ✅ Follow a remote user: `RemoteFollowService.followUser` resolves the `Person`,
  reads their repositories collection via `RemoteRepositoryDirectory`, and fans
  out to a repository follow per public repo (tagged `viaUserActorId`), reusing
  the existing Follow/Accept/Push machinery.
- ✅ Followed users persisted (`remote_user_follows`); the `/following` UI groups
  repositories and their push activity under each followed user.

- ✅ A periodic `FederationResyncScheduler` re-scans each followed user's
  repositories collection (add-only) so repos created after the follow are
  picked up automatically — no re-follow needed.

Remaining follow-ups are add-only reconcile limits (no unfollow on remote
delete/private) and collection pagination — see the gap list in
[forgefed.md](forgefed.md).

### Story 2 — Cross-instance fork with upstream tracking *(issue #12)*

Fork a remote public repository into the caller's local namespace; persist the
upstream link (remote actor URL + clone URL). The clone works over plain git
today — the new work is the upstream model and the UI surface.

Key risk: cloning a **remote-supplied** git URL is a new outbound network
surface. `RemoteUrlGuard` today guards HTTP fetches only; git clone/fetch must
get the same SSRF discipline. Security review required.

### Story 3 — Merge request via federation *(issue #13, epic)*

The submitter forks upstream (Story 2), pushes a branch, and opens a merge
request against the upstream repo on the other instance; the outcome flows back.

**Protocol shape: fork-and-pull via `Offer(Branch)`** (chosen over patch-offer).

| Option | How | Verdict |
|---|---|---|
| **A. Patch-offer** | `Offer{Ticket + embedded patch}` to upstream inbox; target rebuilds MR from the diff | Simpler wire, but no shared git objects; large patches are ugly. **Rejected for v1.** |
| **B. Fork-and-pull** | Fork → push branch → `Offer{Branch}` carrying the branch fetch URL; target `git fetch`es it and opens a local MR referencing it | Matches the user's mental model, keeps real git objects (reviewers see real commits/diffs, can pull the branch), degrades gracefully. **Chosen.** |

Build order inside Story 3:
1. Outbound `Offer(Branch)` — new activity type, enqueued via `DeliveryService`.
2. Inbound `OfferHandler` — validate, `git fetch` submitter branch (SSRF-guarded),
   create a local MR referencing the remote branch.
3. Status back-channel — `Accept`/`Reject`/merge-notify so the submitter sees
   the outcome.

---

## Cross-cutting prerequisites

These bite across Stories 2–3 and should be tracked as they land:

- **Outbound git fetch/clone SSRF guard** — extend `RemoteUrlGuard` (or a sibling)
  to cover the git transport, not just HTTP. New attack surface introduced by
  Story 2, reused by Story 3.
- **Actor lifecycle** (`Delete`/`Update`/`Move`) — still on the gap list in
  [forgefed.md](forgefed.md). A federated MR that references a deleted or moved
  remote fork goes stale silently until this exists.
- **Interop scope** — everything here targets **git-shark ↔ git-shark** first.
  Forgejo/Vervis federation is early and likely incompatible; broadening is a
  later, separately-scoped effort.

## Explicitly out of scope (for now)

- Federated review comments / inline discussion on a merge request.
- Open (non-allowlisted) federation — the mutual peer allowlist stays the trust
  boundary throughout this roadmap.
- Keeping a fork continuously in sync with its upstream beyond the initial clone.
