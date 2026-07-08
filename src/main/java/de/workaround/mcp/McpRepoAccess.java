package de.workaround.mcp;

import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Shared repository resolution for MCP tools: looks a repository up by owner and name and enforces the
 * read-visibility rule (public repos are world-readable, private ones owner-only). A missing or unreadable
 * repository is reported identically as "not found", so private repositories are never revealed to callers
 * who cannot read them.
 */
@ApplicationScoped
class McpRepoAccess
{
	@Inject
	GitRepositoryService repositories;

	@Inject
	AccessPolicy accessPolicy;

	Repository requireReadable(User actor, String owner, String name)
	{
		Repository repo = repositories.find(owner, name)
			.orElseThrow(() -> new ToolCallException("Repository not found: " + owner + "/" + name));
		if (!accessPolicy.canRead(actor, repo))
		{
			throw new ToolCallException("Repository not found: " + owner + "/" + name);
		}
		return repo;
	}
}
