package de.workaround.api;

import java.net.URI;
import java.util.List;

import de.workaround.git.AccessPolicy;
import de.workaround.git.GitBrowseService;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

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
	GitBrowseService browse;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	ApiPrincipal principal;

	@Context
	UriInfo uriInfo;

	@GET
	public List<ApiModels.RepositoryView> list()
	{
		return service.listVisibleTo(principal.orNull()).stream().map(this::view).toList();
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
				.entity(view(repo)).build();
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
		return view(repo);
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
				.entity(view(forked)).build();
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

	/** Build the Gitea repository view, filling the fields that need a git read (default branch, emptiness),
	 *  the request's external base URL (clone/html URLs) and the caller's effective permissions. */
	private ApiModels.RepositoryView view(Repository repo)
	{
		java.nio.file.Path path = service.repositoryPath(repo);
		boolean empty = browse.isEmpty(path);
		String defaultBranch = browse.defaultBranch(path);
		String base = uriInfo.getBaseUri().toString();
		String handle = repo.ownerHandle();
		String cloneUrl = base + "git/" + handle + "/" + repo.name + ".git";
		String htmlUrl = base + "repos/" + handle + "/" + repo.name;
		User caller = principal.orNull();
		ApiModels.PermissionsView permissions = new ApiModels.PermissionsView(accessPolicy.canAdmin(caller, repo),
			accessPolicy.canWrite(caller, repo), accessPolicy.canRead(caller, repo));
		return ApiModels.RepositoryView.of(repo, canSeeParent(repo), empty, defaultBranch, cloneUrl, htmlUrl,
			permissions);
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
