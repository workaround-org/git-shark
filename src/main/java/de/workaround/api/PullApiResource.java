package de.workaround.api;

import java.util.List;
import java.util.UUID;

import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.MergeRequestCommentService;
import de.workaround.git.MergeRequestService;
import de.workaround.model.MergeRequest;
import de.workaround.model.MergeRequestComment;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Gitea-compatible pull requests under {@code /api/v1/repos/{owner}/{name}/pulls}. Internally these are
 * git-shark merge requests; only the wire speaks Gitea's "pull request". A pull is addressed by its
 * per-repository {@code number} (Gitea's {@code index}). Listing/reading follow repository read-visibility;
 * creating, editing, merging and closing require a token and write access (enforced by {@link MergeRequestService}).
 * The line-level review comments are a git-shark feature (not Gitea PR review comments) kept under
 * {@code pulls/{number}/comments}.
 */
@Path("/api/v1/repos/{owner}/{name}/pulls")
@Produces(MediaType.APPLICATION_JSON)
public class PullApiResource
{
	/** Renovate pages until a request returns fewer than a full page; cap the page size so paging terminates. */
	private static final int MAX_PAGE_SIZE = 50;

	@Inject
	GitRepositoryService repositories;

	@Inject
	MergeRequestService mergeRequests;

	@Inject
	MergeRequestCommentService comments;

	@Inject
	MergeRequestComment.Repo commentRepo;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	ApiPrincipal principal;

	@GET
	public List<ApiModels.PullView> list(@PathParam("owner") String owner, @PathParam("name") String name,
		@QueryParam("state") @DefaultValue("open") String state, @QueryParam("page") @DefaultValue("1") int page,
		@QueryParam("limit") @DefaultValue("50") int limit)
	{
		Repository repo = requireReadable(owner, name);
		List<ApiModels.PullView> all = mergeRequests.list(repo).stream()
			.filter(mr -> matchesState(mr, state)).map(ApiModels.PullView::of).toList();
		int size = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
		int from = Math.max(page - 1, 0) * size;
		if (from >= all.size())
		{
			return List.of();
		}
		return all.subList(from, Math.min(from + size, all.size()));
	}

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(@PathParam("owner") String owner, @PathParam("name") String name,
		ApiModels.NewPull request)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequests.create(user, repo, request.title(), request.body(),
			request.head(), request.base());
		return Response.status(Response.Status.CREATED).entity(ApiModels.PullView.of(mr)).build();
	}

	@GET
	@Path("{number}")
	public ApiModels.PullView get(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		Repository repo = requireReadable(owner, name);
		return ApiModels.PullView.of(require(repo, number));
	}

	@PATCH
	@Path("{number}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transactional
	public ApiModels.PullView update(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number, ApiModels.PullEdit request)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = require(repo, number);
		if (request.title() != null || request.body() != null)
		{
			mergeRequests.update(user, mr, request.title(), request.body());
		}
		if ("closed".equalsIgnoreCase(request.state()))
		{
			mergeRequests.close(user, mr);
		}
		else if ("open".equalsIgnoreCase(request.state()))
		{
			mergeRequests.reopen(user, mr);
		}
		return ApiModels.PullView.of(require(repo, number));
	}

	@POST
	@Path("{number}/merge")
	@Transactional
	public ApiModels.PullView merge(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = require(repo, number);
		mergeRequests.merge(user, mr);
		return ApiModels.PullView.of(require(repo, number));
	}

	@GET
	@Path("{number}/comments")
	public List<ApiModels.CommentView> listComments(@PathParam("owner") String owner,
		@PathParam("name") String name, @PathParam("number") int number)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = require(repo, number);
		return comments.list(mr).stream().map(ApiModels.CommentView::of).toList();
	}

	@POST
	@Path("{number}/comments")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response addComment(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number, ApiModels.NewComment request)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = require(repo, number);
		MergeRequestComment comment = comments.add(user, mr, request.filePath(), request.oldLine(),
			request.newLine(), request.body());
		return Response.status(Response.Status.CREATED)
			.entity(ApiModels.CommentView.of(comment)).build();
	}

	@DELETE
	@Path("{number}/comments/{commentId}")
	public Response deleteComment(@PathParam("owner") String owner,
		@PathParam("name") String name, @PathParam("number") int number, @PathParam("commentId") String commentId)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = require(repo, number);
		MergeRequestComment comment = commentRepo.findById(parseId(commentId));
		if (comment == null || !comment.mergeRequest.id.equals(mr.id))
		{
			throw new NotFoundException();
		}
		comments.delete(user, comment);
		return Response.noContent().build();
	}

	private static boolean matchesState(MergeRequest mr, String state)
	{
		if (state == null || "all".equalsIgnoreCase(state))
		{
			return true;
		}
		boolean open = mr.status == MergeRequest.Status.OPEN;
		return "closed".equalsIgnoreCase(state) ? !open : open;
	}

	private static UUID parseId(String id)
	{
		try
		{
			return UUID.fromString(id);
		}
		catch (IllegalArgumentException e)
		{
			throw new NotFoundException();
		}
	}

	private MergeRequest require(Repository repo, int number)
	{
		return mergeRequests.find(repo, number).orElseThrow(NotFoundException::new);
	}

	private Repository requireReadable(String owner, String name)
	{
		Repository repo = repositories.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(principal.orNull(), repo))
		{
			throw new NotFoundException();
		}
		return repo;
	}
}
