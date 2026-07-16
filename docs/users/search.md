# Search

git-shark has a single search box in the header, on every page — the results
page has no search box of its own. Type a term and press Enter to land on the
results page at `/search?q=<term>`; the header box stays prefilled with your
term so you can see (and tweak) what you searched for.

## What it searches

Search looks at two kinds of things at once:

- **Repositories** — matched on the owner handle, the repository name, and the
  description.
- **People** — matched on the username (handle) and the display name.

Matching is a plain **case-insensitive substring** match: searching `ship`
finds `airship`, `Shipping`, and a person whose display name is `Shipwright`.
There is no ranking, fuzzy matching, or full-text search — hits are grouped by
kind (repositories first, then people) and repositories are ordered by name,
people by handle.

## What you see

- Repository hits link straight to the repository page and show the same
  name, visibility badge, and description you see in repository lists.
- People hits show the avatar, handle, and display name, and link to that
  person's [profile page](profile.md#public-profile-page).

You only ever see repositories you are allowed to see: **public repositories,
plus your own private ones** (and private repositories shared with you as a
collaborator or through an organisation). A private repository never appears in
search results for someone who cannot already open it — whether they are logged
in or not.

An empty or blank query is not an error: the page simply prompts you to type
something and shows no results.

## Searching from the API

The same search is available as JSON for scripts and tools — see the
[admin search reference](../admins/search.md) and the REST API docs.
