package de.workaround.mcp;

import java.util.List;

import de.workaround.api.ApiModels;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

/**
 * MCP tools mirroring the repository REST endpoints, so an AI client can manage repositories the same way.
 * Reads follow repository visibility; mutations require a personal access token and repository ownership.
 */
@Singleton
public class RepositoryTools
{
	@Inject
	GitRepositoryService service;

	@Inject
	McpRepoAccess access;

	@Inject
	McpPrincipal principal;

	@Tool(description = "List repositories visible to the caller: all public repositories, plus your own private ones when a valid token is supplied.")
	public List<ApiModels.RepositoryView> listRepositories()
	{
		return service.listVisibleTo(principal.orNull()).stream().map(ApiModels.RepositoryView::of).toList();
	}

	@Tool(description = "Get a single repository by owner username and repository name.")
	public ApiModels.RepositoryView getRepository(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name)
	{
		return ApiModels.RepositoryView.of(access.requireReadable(principal.orNull(), owner, name));
	}

	@Tool(description = "Create a new repository owned by the authenticated user. Requires a personal access token.")
	@Transactional
	public ApiModels.RepositoryView createRepository(
		@ToolArg(description = "Repository name (letters, digits, '.', '_', '-')") String name,
		@ToolArg(description = "Visibility: PUBLIC or PRIVATE", defaultValue = "PUBLIC") String visibility,
		@ToolArg(description = "Optional description", defaultValue = "") String description)
	{
		User user = principal.require();
		Repository.Visibility parsed = "PRIVATE".equalsIgnoreCase(visibility)
			? Repository.Visibility.PRIVATE : Repository.Visibility.PUBLIC;
		Repository repo = service.create(user, name, parsed,
			description == null || description.isBlank() ? null : description);
		return ApiModels.RepositoryView.of(repo);
	}

	@Tool(description = "Delete a repository you own. Requires a personal access token. This is irreversible.")
	@Transactional
	public String deleteRepository(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name)
	{
		User user = principal.require();
		Repository repo = access.requireReadable(user, owner, name);
		service.delete(user, repo);
		return "Deleted repository " + owner + "/" + name;
	}
}
