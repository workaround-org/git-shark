package de.workaround.api;

import java.net.URI;
import java.util.List;

import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.InvalidRepositoryNameException;
import de.workaround.git.RepositoryAlreadyExistsException;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.inject.Inject;
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
 * JSON REST access to repositories under {@code /api/v1/repos}. Reads follow the same visibility rule as the
 * UI (public repositories are world-readable, private ones owner-only, hidden as 404); mutations require a
 * valid access token and repository ownership.
 */
@Path("/api/v1/repos")
@Produces(MediaType.APPLICATION_JSON)
public class RepositoryApiResource
{
	@Inject
	GitRepositoryService service;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	ApiPrincipal principal;

	@GET
	public List<ApiModels.RepositoryView> list()
	{
		return service.listVisibleTo(principal.orNull()).stream().map(ApiModels.RepositoryView::of).toList();
	}

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(ApiModels.NewRepository request)
	{
		User user = principal.require();
		Repository.Visibility visibility = request.visibility() == null
			? Repository.Visibility.PUBLIC : request.visibility();
		String description = request.description() == null || request.description().isBlank()
			? null : request.description();
		try
		{
			Repository repo = service.create(user, request.name(), visibility, description);
			return Response.created(URI.create("/api/v1/repos/" + user.username + "/" + repo.name))
				.entity(ApiModels.RepositoryView.of(repo)).build();
		}
		catch (InvalidRepositoryNameException e)
		{
			return Response.status(Response.Status.BAD_REQUEST).entity(e.getMessage())
				.type(MediaType.TEXT_PLAIN).build();
		}
		catch (RepositoryAlreadyExistsException e)
		{
			return Response.status(Response.Status.CONFLICT).entity(e.getMessage())
				.type(MediaType.TEXT_PLAIN).build();
		}
	}

	@GET
	@Path("{owner}/{name}")
	public ApiModels.RepositoryView get(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		return ApiModels.RepositoryView.of(repo, canSeeParent(repo));
	}

	@POST
	@Path("{owner}/{name}/fork")
	public Response fork(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		User user = principal.require();
		Repository source = requireReadable(owner, name);
		try
		{
			Repository forked = service.fork(user, source);
			return Response.created(URI.create("/api/v1/repos/" + user.username + "/" + forked.name))
				.entity(ApiModels.RepositoryView.of(forked, canSeeParent(forked))).build();
		}
		catch (RepositoryAlreadyExistsException e)
		{
			return Response.status(Response.Status.CONFLICT).entity(e.getMessage())
				.type(MediaType.TEXT_PLAIN).build();
		}
	}

	@DELETE
	@Path("{owner}/{name}")
	public Response delete(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		// delegate ownership enforcement to the service (throws ForbiddenOperationException -> 403)
		service.delete(user, repo);
		return Response.noContent().build();
	}

	/** Whether the caller may see this repo's fork parent — true only when it is a fork of a repo they can read. */
	private boolean canSeeParent(Repository repo)
	{
		return repo.parent != null && accessPolicy.canRead(principal.orNull(), repo.parent);
	}

	private Repository requireReadable(String owner, String name)
	{
		Repository repo = service.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(principal.orNull(), repo))
		{
			// hide existence of private repositories
			throw new NotFoundException();
		}
		return repo;
	}
}
