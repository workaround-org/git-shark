# SSH transport & clone-URL construction

How git-shark serves Git over SSH, and how the SSH clone/push URLs shown in the
UI are built. For the admin-facing configuration, see
[Getting started → Configuration reference](../admins/getting-started.md#configuration-reference).

## Component map

| Component | Responsibility |
|---|---|
| `de.workaround.ssh.GitSshServer` | Starts the embedded Apache SSHD daemon at boot, binds `gitshark.ssh.port`, persists the host key. Public-key auth only; no passwords, no shell. |
| `de.workaround.ssh.GitSshAuthenticator` | Resolves a presented public key to a user via `SshKeyService`. |
| `de.workaround.ssh.GitSshCommandFactory` | Maps the incoming `git-upload-pack` / `git-receive-pack` command to a repository. |
| `de.workaround.web.CloneUrls` | Pure helper that renders the `ssh://` URL string shown in the UI. |
| `de.workaround.web.RepoNavService` | Assembles the `RepoNav` record (clone URLs, counts, …) rendered by the repo sidebar and empty-repo quick start. |

## Bind port vs. external (advertised) port — the key decision

There are **two** SSH port config values, and they are deliberately decoupled:

- `gitshark.ssh.port` (env `GITSHARK_SSH_PORT`, default **2222**) — the port the
  embedded server actually **binds** (`GitSshServer.onStart` → `sshd.setPort(port)`).
  Kept above 1024 by default so the container process never needs `CAP_NET_BIND_SERVICE`
  or root to listen.
- `gitshark.ssh.external-port` (env `GITSHARK_SSH_EXTERNAL_PORT`, default **22**) — the
  port written into the **advertised** clone/push URLs (`RepoNavService` injects it as
  `sshExternalPort` and passes it to `CloneUrls.ssh(...)`). It has **no runtime effect**
  on the server; it exists only so the URL can reflect a host/ingress port mapping.

**Why the split exists.** The common deployment publishes the container's `2222` on host
port `22` (`"22:2222"` in Compose, or a `Service`/ingress mapping in Kubernetes). Clients
then connect on the clean standard port, but the process inside the container still binds
an unprivileged port. Before this split the UI printed the *bind* port (`2222`), producing
URLs like `ssh://git@host:2222/...` even when SSH was reachable on `22` — wrong and ugly.
Advertising a separate external port fixes both.

**Invariant the admin must uphold:** `external-port` must equal the host port SSH is
actually reachable on. git-shark cannot verify this — it only renders the string. A
mismatch yields a copy-paste clone command that connects to a closed port. This is called
out in the admin docs; keep both docs consistent if the defaults change.

## URL rendering rules (`CloneUrls`)

- `CloneUrls.DEFAULT_SSH_PORT = 22`. When `external-port == 22` it emits the short
  **scp-like** form `git@host:owner/repo.git` (as GitHub does) — no `ssh://`, no port.
  Any other port forces the explicit `ssh://git@host:2222/owner/repo.git` form, because the
  scp shorthand cannot express a port (git would parse the number as part of the path).
- The **host** comes from the incoming web request (`uriInfo.getBaseUri().getHost()`),
  not a separate config value — SSH is assumed to share the web hostname. There is no
  independent SSH-host override today (see gaps below).
- The **HTTP(S)** clone URL is still derived purely from the request base URI
  (`uriInfo.getBaseUri().resolve(...)`), which already normalises away default ports
  (80/443). Only the SSH URL needed the explicit port handling.

Both URLs land on the `RepoNav` record (`httpUrl`, `sshUrl`) and are rendered by
`templates/RepositoryResource/sidebar.html` (clone dialog) and
`templates/RepositoryResource/overview.html` (empty-repo quick start, HTTPS/SSH toggle).

## What works today

- Embedded public-key SSH Git transport on a configurable, unprivileged bind port.
- Separate advertised SSH port so URLs match a host/ingress port mapping; on port 22 the
  URL uses the short scp-like `git@host:owner/repo.git` form.
- Empty-repo quick start offers both HTTPS and SSH push commands via a client-side toggle.

## What still needs to be implemented

- **Separate SSH hostname.** The SSH host is hard-tied to the web request host. A
  deployment terminating SSH on a different name (e.g. `ssh.gitshark.example`) cannot
  advertise it — would need a `gitshark.ssh.external-host` config, threaded through
  `CloneUrls` the same way `external-port` is.
- **Runtime validation** that `external-port` is actually reachable — currently purely
  advisory via docs.
