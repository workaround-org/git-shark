# Search

Search spans repositories and people and is exposed both as a page and as JSON.
It needs **no configuration** — there are no `GITSHARK_*` properties, no new
tables, and no background jobs. It reads existing `repositories` and `users`
rows directly.

## Endpoints

| Method & path | Auth | Returns |
|---|---|---|
| `GET /search?q=<term>` | Optional (session) | HTML results page |
| `GET /api/v1/search?q=<term>` | Optional (Bearer token) | JSON hits |

Both endpoints are open to anonymous callers. When a personal access token (API)
or session (UI) is present, the caller's own private repositories become
eligible; otherwise only public repositories are searched. People results are
unaffected by authentication.

## JSON shape

```json
{
  "repositories": [
    { "owner": "alice", "name": "widgets", "visibility": "PUBLIC",
      "description": "gadgets", "createdAt": "2026-07-14T08:00:00Z" }
  ],
  "persons": [
    { "username": "alice", "displayName": "Alice Example" }
  ]
}
```

A blank or missing `q` returns `200` with two empty arrays — never an error.

## Matching semantics

- Case-insensitive **substring** match (SQL `LIKE '%term%'` for people; in-memory
  substring for repositories). No ranking, no full-text.
- Repositories match on owner handle, name, and description; people on username
  and display name.
- Only **onboarded** users (those who have chosen a handle) appear in people
  results.
- Repository visibility is enforced by reusing the same "visible to this user"
  query the rest of the platform uses, so search cannot leak a private
  repository.

## Operational notes

- No caching and no dedicated index: each search is a live query. For the
  repository side it lists the caller's visible repositories and filters them in
  memory, which is fine at the scale git-shark targets. If a deployment grows to
  a very large repository count, this is the first place to add a query-side
  filter or an index.
