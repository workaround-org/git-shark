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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JSON REST access to a repository's merge requests under {@code /api/v1/repos/{owner}/{name}/merge-requests}.
 * Merge requests are addressed by their per-repository number. Listing/reading follow repository read-visibility;
 * creating, merging and closing require a token and repository ownership (enforced by {@link MergeRequestService}).
 */
@Path("/api/v1/repos/{owner}/{name}/merge-requests")
@Produces(MediaType.APPLICATION_JSON)
public class MergeRequestApiResource
{
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
	public List<ApiModels.MergeRequestView> list(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		return mergeRequests.list(repo).stream().map(ApiModels.MergeRequestView::of).toList();
	}

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(@PathParam("owner") String owner, @PathParam("name") String name,
		ApiModels.NewMergeRequest request)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequests.create(user, repo, request.title(), request.description(),
			request.sourceBranch(), request.targetBranch());
		return Response.status(Response.Status.CREATED)
			.entity(ApiModels.MergeRequestView.of(mr)).build();
	}

	@GET
	@Path("{number}")
	public ApiModels.MergeRequestView get(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		Repository repo = requireReadable(owner, name);
		return ApiModels.MergeRequestView.of(require(repo, number));
	}

	@POST
	@Path("{number}/merge")
	@Transactional
	public ApiModels.MergeRequestView merge(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = require(repo, number);
		mergeRequests.merge(user, mr);
		return ApiModels.MergeRequestView.of(require(repo, number));
	}

	@POST
	@Path("{number}/close")
	@Transactional
	public ApiModels.MergeRequestView close(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = require(repo, number);
		mergeRequests.close(user, mr);
		return ApiModels.MergeRequestView.of(require(repo, number));
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
