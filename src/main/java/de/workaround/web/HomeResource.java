package de.workaround.web;

import java.net.URI;
import java.util.List;

import de.workaround.account.CurrentUser;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.InvalidRepositoryNameException;
import de.workaround.git.RepositoryAlreadyExistsException;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.TEXT_HTML)
public class HomeResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance home(List<Repository> repositories, User user);

		static native TemplateInstance landing();

		static native TemplateInstance newRepo(String error);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService service;

	@GET
	public TemplateInstance home()
	{
		User user = currentUser.get();
		if (user == null)
		{
			return Templates.landing();
		}
		return Templates.home(service.listVisibleTo(user), user);
	}

	@GET
	@Path("explore")
	public TemplateInstance explore()
	{
		User user = currentUser.get();
		return Templates.home(service.listVisibleTo(user), user);
	}

	@GET
	@Path("login")
	public Response login()
	{
		// Path is protected (authenticated); OIDC intercepts anonymous access and
		// redirects here after login, then we send the user to their repository list.
		currentUser.require();
		return Response.seeOther(URI.create("/")).build();
	}

	@GET
	@Path("repos/new")
	public TemplateInstance newRepo()
	{
		currentUser.require();
		return Templates.newRepo(null);
	}

	@POST
	@Path("repos")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response create(@FormParam("name") String name, @FormParam("visibility") String visibility,
		@FormParam("description") String description)
	{
		User user = currentUser.require();
		try
		{
			Repository.Visibility parsed = "PRIVATE".equalsIgnoreCase(visibility)
				? Repository.Visibility.PRIVATE : Repository.Visibility.PUBLIC;
			service.create(user, name, parsed, description == null || description.isBlank() ? null : description);
			return Response.seeOther(URI.create("/repos/" + user.username + "/" + name)).build();
		}
		catch (InvalidRepositoryNameException | RepositoryAlreadyExistsException e)
		{
			return Response.status(Response.Status.BAD_REQUEST)
				.entity(Templates.newRepo(e.getMessage()))
				.build();
		}
	}

}
