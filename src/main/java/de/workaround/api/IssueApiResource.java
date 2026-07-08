package de.workaround.api;

import java.util.List;

import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.InvalidIssueException;
import de.workaround.git.IssueService;
import de.workaround.model.Issue;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * JSON REST access to a repository's issues under {@code /api/v1/repos/{owner}/{name}/issues}. Issues are
 * addressed by their per-repository number. Listing/reading follow repository read-visibility; creating,
 * transitioning and deleting require a token and repository ownership (enforced by {@link IssueService}).
 */
@Path("/api/v1/repos/{owner}/{name}/issues")
@Produces(MediaType.APPLICATION_JSON)
public class IssueApiResource
{
	@Inject
	GitRepositoryService repositories;

	@Inject
	IssueService issues;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	ApiPrincipal principal;

	@GET
	public List<ApiModels.IssueView> list(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		return issues.list(repo).stream().map(ApiModels.IssueView::of).toList();
	}

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(@PathParam("owner") String owner, @PathParam("name") String name,
		ApiModels.NewIssue request)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		Issue issue = issues.create(user, repo, request.title(), request.description());
		return Response.status(Response.Status.CREATED).entity(ApiModels.IssueView.of(issue)).build();
	}

	@GET
	@Path("{number}")
	public ApiModels.IssueView get(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		Repository repo = requireReadable(owner, name);
		return ApiModels.IssueView.of(require(repo, number));
	}

	@PATCH
	@Path("{number}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transactional
	public ApiModels.IssueView update(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number, ApiModels.IssueStatusUpdate request)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		Issue issue = require(repo, number);
		if (request.status() == null)
		{
			throw new InvalidIssueException("status must be provided");
		}
		issues.updateStatus(user, issue, request.status());
		return ApiModels.IssueView.of(require(repo, number));
	}

	@DELETE
	@Path("{number}")
	public Response delete(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		Issue issue = require(repo, number);
		issues.delete(user, issue);
		return Response.noContent().build();
	}

	private Issue require(Repository repo, int number)
	{
		return issues.find(repo, number).orElseThrow(NotFoundException::new);
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
