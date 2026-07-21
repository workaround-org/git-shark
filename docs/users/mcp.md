# Using git-shark from an AI client (MCP)

git-shark exposes an [MCP](https://modelcontextprotocol.io) (Model Context Protocol)
server, so AI clients such as Claude Code, Claude Desktop/claude.ai, or any other
MCP-capable tool can browse and manage your repositories, issues, and merge requests
by talking to your instance directly.

- **URL:** `https://<your-instance>/mcp`
- **Transport:** Streamable HTTP (use this one; the legacy HTTP/SSE variant also
  exists but Streamable HTTP is the supported transport)
- **Auth:** your personal access token as `Authorization: Bearer <token>`

The MCP tools enforce exactly the same rules as the web UI and REST API: reading
public repositories works without a token, everything else acts as the user the
token belongs to.

## 1. Create a personal access token

1. Log in and open **Account → Access tokens** (`/settings/tokens`).
2. Give the token a label (e.g. `claude`) and create it.
3. Copy the token now — it is shown only once. This is the same kind of token used
   for the REST API and for `git push` over HTTP.

Revoke it any time on the same page.

## 2. Connect a client

### Claude Code

```bash
claude mcp add --transport http gitshark https://<your-instance>/mcp \
  --header "Authorization: Bearer <your-token>"
```

Then verify with `/mcp` inside Claude Code — the `gitshark` server and its tools
should be listed. Without the header everything still connects, but only public
reads work.

### Claude Desktop / claude.ai

Custom connectors in Claude Desktop and claude.ai don't support custom headers
directly. Bridge with [`mcp-remote`](https://www.npmjs.com/package/mcp-remote) in
`claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "gitshark": {
      "command": "npx",
      "args": [
        "mcp-remote",
        "https://<your-instance>/mcp",
        "--header",
        "Authorization: Bearer <your-token>"
      ]
    }
  }
}
```

### Other MCP clients

Any client that supports Streamable HTTP servers with a custom header works the
same way: endpoint `https://<your-instance>/mcp`, header
`Authorization: Bearer <your-token>`.

## What the tools can do

| Area | Tools | Token needed? |
|---|---|---|
| Repositories | `listRepositories`, `getRepository` | no (public repos) |
| | `createRepository`, `forkRepository`, `deleteRepository` | yes |
| Issues | `listIssues`, `getIssue`, `listIssueComments` | no (public repos) |
| | `createIssue`, `updateIssue`, `assignIssue`, `updateIssueStatus`, `deleteIssue` | yes (owner or collaborator) |
| | `addIssueComment` | yes (any reader of the repository) |
| Merge requests | `listMergeRequests`, `getMergeRequest`, `listMergeRequestComments` | no (public repos) |
| | `createMergeRequest`, `mergeMergeRequest`, `closeMergeRequest` | yes (owner or collaborator) |
| | `addMergeRequestComment` | yes (any reader of the repository) |
| You | `currentUser` | yes |

Private repositories are visible to their owner and collaborators only — with a
token, reads cover your own private repositories too.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Tool call fails with `A valid personal access token is required` | The client didn't send the `Authorization: Bearer` header, or the token was revoked. Re-check the header configuration and create a fresh token under **Account → Access tokens**. |
| Only some repositories are listed | Without a token you only see public repositories. Add the header to see your own private ones. |
| Client can't connect at all | The MCP endpoint lives on the same host as the web UI — make sure `https://<your-instance>/mcp` is reachable through your instance's HTTPS proxy and you're not pointing at the legacy `/mcp/sse` path with a Streamable-HTTP client. |
