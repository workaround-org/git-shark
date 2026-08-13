# Releases

Releases annotate git tags with a title, Markdown notes and a pre-release flag,
and expose source-code archives of any ref. **No configuration is needed** — the
feature is always on and adds no `GITSHARK_*` property.

## Endpoints

| Method | Path | Access |
|---|---|---|
| `GET` | `/repos/{owner}/{name}/releases` | Repository read |
| `GET` | `/repos/{owner}/{name}/releases/new` | Repository write |
| `POST` | `/repos/{owner}/{name}/releases` | Repository write |
| `GET` | `/repos/{owner}/{name}/releases/tag/{tag}` | Repository read |
| `GET`/`POST` | `/repos/{owner}/{name}/releases/tag/{tag}/edit` | Repository write |
| `POST` | `/repos/{owner}/{name}/releases/tag/{tag}/delete` | Repository write |
| `GET` | `/repos/{owner}/{name}/archive/{ref}.zip` | Repository read |
| `GET` | `/repos/{owner}/{name}/archive/{ref}.tar.gz` | Repository read |
| `GET` | `/api/v1/repos/{owner}/{name}/releases` | Repository read (token optional) |
| `POST` | `/api/v1/repos/{owner}/{name}/releases` | Token + repository write |
| `GET` | `/api/v1/repos/{owner}/{name}/releases/latest` | Repository read |
| `GET`/`PATCH`/`DELETE` | `/api/v1/repos/{owner}/{name}/releases/tags/{tag}` | Read / token + write |

Write access follows the single access policy: personal-repo owner and
collaborators, organisation MEMBER and above. A private repository's release and
archive endpoints answer `404` for anyone without read access, so they never
confirm the repository exists.

## Archives

`GET …/archive/{ref}.zip|.tar.gz` streams the tree of any ref (tag, branch or
commit id) straight out of the object database:

- Entries are nested under a single `<repo>-<ref>/` directory; slashes in the ref
  collapse to dashes.
- Both formats are produced by the JDK (`ZipOutputStream`, ustar + `GZIPOutputStream`) —
  no extra archiving dependency, and no temporary files on disk.
- Blobs are copied through, never buffered whole, so a large tree does not scale
  memory with repository size. There is **no size cap and no rate limit** — a
  public instance serving very large repositories should rate-limit this path at
  the reverse proxy, the same way a clone would be.
- Submodules are skipped (they have no content here); symlinks become regular
  files holding their target path.

Responses carry `Content-Disposition: attachment` and `application/zip` /
`application/gzip`. They are generated per request and are not cached.

## Tag creation

Publishing a release whose tag does not exist yet creates an **annotated tag**
in-core against the bare repository, authored by the publishing user (display
name + account email, falling back to `<username>@localhost` when the account has
no address). This is a real ref write in `GITSHARK_STORAGE_ROOT`, so it shows up
for every clone and push mirror afterwards. Existing tags are never moved or
rewritten, and deleting a release never deletes its tag.

## Database

One table, added in `V32__releases.sql`:

| Table | Columns | Notes |
|---|---|---|
| `releases` | `id`, `repository_id`, `author_id`, `tag_name`, `title`, `body`, `commit_id`, `prerelease`, `created_at` | `unique (repository_id, tag_name)` — one release per tag; `ON DELETE CASCADE` from both `repositories` and `users` |

Release notes live in the database; the tag and its objects live in the git
repository. A restore therefore needs both stores in sync — see
[Persistent data](persistent-data.md).

## Troubleshooting

| Symptom | Cause |
|---|---|
| `400` "does not exist — pick a target to create it from" | The tag is unknown and no target branch was submitted. |
| `400` "already has a release" | One release per tag; edit the existing one instead. |
| `400` "is not a valid tag name" | git ref rules (no spaces, no `..`, no trailing `/`). |
| Archive returns `404` | The ref does not resolve, or the suffix is neither `.zip` nor `.tar.gz`. |
| Archive download stalls on a huge repository | Expected — it streams; put a proxy timeout/rate limit in front if this is abused. |
