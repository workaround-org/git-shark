package de.workaround.mcp;

import java.util.List;

import de.workaround.api.ApiModels;
import de.workaround.git.MergeRequestCommentService;
import de.workaround.git.MergeRequestService;
import de.workaround.model.MergeRequest;
import de.workaround.model.MergeRequestComment;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkiverse.mcp.server.ToolCallException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

/**
 * MCP tools mirroring the merge-request REST endpoints, including line-level review comments. Merge requests
 * are addressed by their per-repository number. Reading follows repository visibility; creating, merging and
 * closing require a token and ownership, while commenting only requires read access.
 */
@Singleton
public class MergeRequestTools
{
	@Inject
	MergeRequestService mergeRequests;

	@Inject
	MergeRequestCommentService comments;

	@Inject
	McpRepoAccess access;

	@Inject
	McpPrincipal principal;

	@Tool(description = "List the merge requests of a repository.")
	public List<ApiModels.PullView> listMergeRequests(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name)
	{
		Repository repo = access.requireReadable(principal.orNull(), owner, name);
		return mergeRequests.list(repo).stream().map(ApiModels.PullView::of).toList();
	}

	@Tool(description = "Get a single merge request by its number.")
	public ApiModels.PullView getMergeRequest(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name, @ToolArg(description = "Merge request number") int number)
	{
		Repository repo = access.requireReadable(principal.orNull(), owner, name);
		return ApiModels.PullView.of(requireMergeRequest(repo, number));
	}

	@Tool(description = "Open a merge request from a source branch into a target branch. Requires a token and ownership.")
	@Transactional
	public ApiModels.PullView createMergeRequest(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name, @ToolArg(description = "Title") String title,
		@ToolArg(description = "Optional description", defaultValue = "") String description,
		@ToolArg(description = "Source branch") String sourceBranch,
		@ToolArg(description = "Target branch") String targetBranch)
	{
		User user = principal.require();
		Repository repo = access.requireReadable(user, owner, name);
		MergeRequest mr = mergeRequests.create(user, repo, title,
			description == null || description.isBlank() ? null : description, sourceBranch, targetBranch);
		return ApiModels.PullView.of(mr);
	}

	@Tool(description = "Merge an open merge request. Requires a token and ownership.")
	@Transactional
	public ApiModels.PullView mergeMergeRequest(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name, @ToolArg(description = "Merge request number") int number)
	{
		User user = principal.require();
		Repository repo = access.requireReadable(user, owner, name);
		MergeRequest mr = requireMergeRequest(repo, number);
		mergeRequests.merge(user, mr);
		return ApiModels.PullView.of(requireMergeRequest(repo, number));
	}

	@Tool(description = "Close an open merge request without merging. Requires a token and ownership.")
	@Transactional
	public ApiModels.PullView closeMergeRequest(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name, @ToolArg(description = "Merge request number") int number)
	{
		User user = principal.require();
		Repository repo = access.requireReadable(user, owner, name);
		MergeRequest mr = requireMergeRequest(repo, number);
		mergeRequests.close(user, mr);
		return ApiModels.PullView.of(requireMergeRequest(repo, number));
	}

	@Tool(description = "List the line-level review comments of a merge request.")
	public List<ApiModels.CommentView> listMergeRequestComments(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name, @ToolArg(description = "Merge request number") int number)
	{
		Repository repo = access.requireReadable(principal.orNull(), owner, name);
		MergeRequest mr = requireMergeRequest(repo, number);
		return comments.list(mr).stream().map(ApiModels.CommentView::of).toList();
	}

	@Tool(description = "Add a line-level review comment to a merge request's diff. Requires a token; any reader may comment. "
		+ "The comment must anchor to a line present in the diff: use -1 for the side a line does not exist on "
		+ "(added lines have no old-side number, deleted lines no new-side number).")
	@Transactional
	public ApiModels.CommentView addMergeRequestComment(@ToolArg(description = "Owner username") String owner,
		@ToolArg(description = "Repository name") String name, @ToolArg(description = "Merge request number") int number,
		@ToolArg(description = "File path the comment anchors to") String filePath,
		@ToolArg(description = "1-based old-side line number, or -1 for an added line") int oldLine,
		@ToolArg(description = "1-based new-side line number, or -1 for a deleted line") int newLine,
		@ToolArg(description = "Comment body") String body)
	{
		User user = principal.require();
		Repository repo = access.requireReadable(user, owner, name);
		MergeRequest mr = requireMergeRequest(repo, number);
		MergeRequestComment comment = comments.add(user, mr, filePath, oldLine, newLine, body);
		return ApiModels.CommentView.of(comment);
	}

	private MergeRequest requireMergeRequest(Repository repo, int number)
	{
		return mergeRequests.find(repo, number)
			.orElseThrow(() -> new ToolCallException("Merge request !" + number + " not found"));
	}
}
