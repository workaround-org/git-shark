package de.workaround.web;

import java.net.URI;
import java.util.List;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.InvalidCollaboratorException;
import de.workaround.model.Repository;
import de.workaround.model.RepositoryCollaborator;
import de.workaround.model.User;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
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
 * Owner-only collaborator management under the repository settings. Non-owners who can see the
 * repository get 403; users who cannot see it get 404 so private repositories stay hidden.
 * Managing collaborators deliberately stays owner-only — a collaborator's write access covers git
 * and repo content, not granting access to others.
 */
@Path("/repos/{owner}/{name}/settings/collaborators")
@Produces(MediaType.TEXT_HTML)
public class CollaboratorResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance collaborators(Repository repo, RepoNav nav,
			List<RepositoryCollaborator> collaborators, String error);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService service;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	de.workaround.git.CollaboratorService collaboratorService;

	@Inject
	RepoNavService repoNav;

	@Context
	UriInfo uriInfo;

	@GET
	public TemplateInstance page(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireOwned(owner, name);
		return Templates.collaborators(repo, repoNav.build(repo, uriInfo), collaboratorService.list(repo), null);
	}

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response add(@PathParam("owner") String owner, @PathParam("name") String name,
		@FormParam("username") String username)
	{
		Repository repo = requireOwned(owner, name);
		try
		{
			collaboratorService.add(currentUser.require(), repo, username);
		}
		catch (InvalidCollaboratorException e)
		{
			return Response.status(Response.Status.BAD_REQUEST)
				.entity(Templates.collaborators(repo, repoNav.build(repo, uriInfo), collaboratorService.list(repo),
					e.getMessage()))
				.build();
		}
		return backToSettings(owner, name);
	}

	@POST
	@Path("{username}/remove")
	public Response remove(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("username") String username)
	{
		Repository repo = requireOwned(owner, name);
		collaboratorService.remove(currentUser.require(), repo, username);
		return backToSettings(owner, name);
	}

	private Repository requireOwned(String owner, String name)
	{
		Repository repo = service.find(owner, name).orElseThrow(NotFoundException::new);
		User user = currentUser.get();
		if (!accessPolicy.canRead(user, repo))
		{
			// hide existence of private repositories
			throw new NotFoundException();
		}
		if (user == null || !user.id.equals(repo.owner.id))
		{
			throw new ForbiddenOperationException("Only the repository owner can manage collaborators");
		}
		return repo;
	}

	private static Response backToSettings(String owner, String name)
	{
		return Response.seeOther(URI.create("/repos/" + owner + "/" + name + "/settings/collaborators")).build();
	}

}
