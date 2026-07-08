package de.workaround.mcp;

import java.util.List;

import de.workaround.api.ApiModels;
import de.workaround.git.IssueService;
import de.workaround.model.Issue;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

/**
 * MCP tools mirroring the issue REST endpoints. Issues are addressed by their per-repository number.
 * Reading follows repository visibility; creating, transitioning and deleting require a token and ownership.
 */
@Singleton
public class IssueTools
{
	@Inject
	IssueService issues;

	@Inject
	McpRepoAccess access;

	@Inject
	McpPrincipal principal;

	@Tool(description = "List the issues of a repository.")
	public List<ApiModels.IssueView> listIssues(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name)
	{
		Repository repo = access.requireReadable(principal.orNull(), owner, name);
		return issues.list(repo).stream().map(ApiModels.IssueView::of).toList();
	}

	@Tool(description = "Get a single issue by its number.")
	public ApiModels.IssueView getIssue(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name, @ToolArg(description = "Issue number") int number)
	{
		Repository repo = access.requireReadable(principal.orNull(), owner, name);
		return ApiModels.IssueView.of(requireIssue(repo, number));
	}

	@Tool(description = "Create an issue in a repository. Requires a personal access token and repository ownership.")
	@Transactional
	public ApiModels.IssueView createIssue(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name, @ToolArg(description = "Issue title") String title,
		@ToolArg(description = "Optional description", defaultValue = "") String description)
	{
		User user = principal.require();
		Repository repo = access.requireReadable(user, owner, name);
		Issue issue = issues.create(user, repo, title, description == null || description.isBlank() ? null : description);
		return ApiModels.IssueView.of(issue);
	}

	@Tool(description = "Update an issue's status. Requires a token and ownership.")
	@Transactional
	public ApiModels.IssueView updateIssueStatus(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name, @ToolArg(description = "Issue number") int number,
		@ToolArg(description = "New status: PLANNED, IN_DEVELOPMENT or DONE") String status)
	{
		User user = principal.require();
		Repository repo = access.requireReadable(user, owner, name);
		Issue issue = requireIssue(repo, number);
		issues.updateStatus(user, issue, parseStatus(status));
		return ApiModels.IssueView.of(requireIssue(repo, number));
	}

	@Tool(description = "Delete an issue by its number. Requires a token and ownership.")
	@Transactional
	public String deleteIssue(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name, @ToolArg(description = "Issue number") int number)
	{
		User user = principal.require();
		Repository repo = access.requireReadable(user, owner, name);
		Issue issue = requireIssue(repo, number);
		issues.delete(user, issue);
		return "Deleted issue #" + number;
	}

	private Issue requireIssue(Repository repo, int number)
	{
		return issues.find(repo, number)
			.orElseThrow(() -> new ToolCallException("Issue #" + number + " not found"));
	}

	private static Issue.Status parseStatus(String status)
	{
		try
		{
			return Issue.Status.valueOf(status.trim().toUpperCase());
		}
		catch (IllegalArgumentException | NullPointerException e)
		{
			throw new ToolCallException("Invalid status: " + status + " (expected PLANNED, IN_DEVELOPMENT or DONE)");
		}
	}
}
