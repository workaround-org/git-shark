package de.workaround.api;

import java.util.List;

import de.workaround.git.AccessPolicy;
import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Gitea commit-status endpoints. git-shark has no status store yet (CI runners exist but are unwired), so the
 * combined/list reads report an empty, all-clear set and {@code POST /statuses/{sha}} echoes the posted status
 * without persisting it — enough for Renovate to treat a branch as passing and proceed. The {@code ref} segment
 * is matched greedily so slash-bearing branch names resolve. Reads follow repository visibility; posting a
 * status needs a token and write access.
 */
@Path("/api/v1/repos/{owner}/{name}")
@Produces(MediaType.APPLICATION_JSON)
public class CommitStatusApiResource
{
	@Inject
	GitRepositoryService repositories;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	ApiPrincipal principal;

	@GET
	@Path("commits/{ref:.+}/status")
	public ApiModels.CombinedStatusView combined(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("ref") String ref)
	{
		requireReadable(owner, name);
		return ApiModels.CombinedStatusView.allClear(ref);
	}

	@GET
	@Path("commits/{ref:.+}/statuses")
	public List<ApiModels.CommitStatusView> list(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("ref") String ref)
	{
		requireReadable(owner, name);
		return List.of();
	}

	@POST
	@Path("statuses/{sha}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("sha") String sha, ApiModels.NewStatus request)
	{
		User user = principal.require();
		Repository repo = repositories.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(principal.orNull(), repo))
		{
			throw new NotFoundException();
		}
		if (!accessPolicy.canWrite(user, repo))
		{
			throw new ForbiddenOperationException("Only the repository owner or a collaborator can post statuses");
		}
		ApiModels.CommitStatusView echoed = new ApiModels.CommitStatusView(0, request.state(), request.context(),
			request.description(), request.targetUrl());
		return Response.status(Response.Status.CREATED).entity(echoed).build();
	}

	private void requireReadable(String owner, String name)
	{
		Repository repo = repositories.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(principal.orNull(), repo))
		{
			throw new NotFoundException();
		}
	}
}
