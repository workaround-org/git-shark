package de.workaround.web;

/**
 * Builds the clone/remote URLs shown in the UI. Kept separate from the request-derived HTTP URL so the SSH URL can use
 * a configured <em>external</em> port that may differ from the port the embedded server binds inside the container.
 * On the default port (22) it emits the short scp-like form ({@code git@host:owner/repo.git}, as GitHub does); any
 * other port forces the explicit {@code ssh://host:port/...} form, because scp shorthand cannot express a port.
 */
public final class CloneUrls
{
	/** The port git assumes for {@code ssh://} URLs when none is given. */
	public static final int DEFAULT_SSH_PORT = 22;

	private CloneUrls()
	{
	}

	/**
	 * @param host          the externally reachable SSH host (the web request host)
	 * @param externalPort  the port users connect to (may differ from the container bind port); {@code 22} is omitted
	 * @param owner         the repository owner handle
	 * @param repoName      the repository name (without {@code .git})
	 */
	public static String ssh(String host, int externalPort, String owner, String repoName)
	{
		if (externalPort == DEFAULT_SSH_PORT)
		{
			return "git@" + host + ":" + owner + "/" + repoName + ".git";
		}
		return "ssh://git@" + host + ":" + externalPort + "/" + owner + "/" + repoName + ".git";
	}
}
