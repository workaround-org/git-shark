package de.workaround.web;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import de.workaround.account.CurrentUser;
import de.workaround.account.OrganisationService;
import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.InvalidRepositoryNameException;
import de.workaround.git.RepositoryAlreadyExistsException;
import de.workaround.git.RepositoryPinService;
import de.workaround.model.Organisation;
import de.workaround.model.OrganisationMember;
import de.workaround.model.Repository;
import de.workaround.model.User;
import de.workaround.notify.NotificationItem;
import de.workaround.notify.NotificationService;
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

		static native TemplateInstance dashboard(List<Repository> pinned, List<NotificationItem> notifications,
			List<DashboardRepo> repositories, User user);

		static native TemplateInstance landing();

		static native TemplateInstance newRepo(String error, List<Organisation> orgs);
	}

	/** A repository row in the dashboard's full list, carrying whether the current user has pinned it. */
	public record DashboardRepo(Repository repo, boolean pinned)
	{
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService service;

	@Inject
	RepositoryPinService pinService;

	@Inject
	NotificationService notifications;

	@Inject
	OrganisationService organisations;

	@GET
	public TemplateInstance home()
	{
		User user = currentUser.get();
		if (user == null)
		{
			return Templates.landing();
		}
		List<Repository> pinned = pinService.listPinned(user);
		Set<UUID> pinnedIds = pinned.stream().map(repo -> repo.id).collect(Collectors.toSet());
		List<DashboardRepo> rows = service.listVisibleTo(user).stream()
			.map(repo -> new DashboardRepo(repo, pinnedIds.contains(repo.id)))
			.toList();
		return Templates.dashboard(pinned, notifications.notificationsFor(user), rows, user);
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
		User user = currentUser.require();
		return Templates.newRepo(null, organisations.ownedBy(user));
	}

	@POST
	@Path("repos")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response create(@FormParam("name") String name, @FormParam("visibility") String visibility,
		@FormParam("description") String description, @FormParam("owner") String ownerHandle)
	{
		User user = currentUser.require();
		try
		{
			Repository.Visibility parsed = "PRIVATE".equalsIgnoreCase(visibility)
				? Repository.Visibility.PRIVATE : Repository.Visibility.PUBLIC;
			String trimmedDescription = description == null || description.isBlank() ? null : description;
			Repository repo;
			if (ownerHandle == null || ownerHandle.isBlank() || ownerHandle.equals(user.username))
			{
				repo = service.create(user, name, parsed, trimmedDescription);
			}
			else
			{
				repo = createInOrganisation(user, ownerHandle, name, parsed, trimmedDescription);
			}
			return Response.seeOther(URI.create("/repos/" + repo.ownerHandle() + "/" + repo.name)).build();
		}
		catch (InvalidRepositoryNameException | RepositoryAlreadyExistsException e)
		{
			return Response.status(Response.Status.BAD_REQUEST)
				.entity(Templates.newRepo(e.getMessage(), organisations.ownedBy(user)))
				.build();
		}
	}

	private Repository createInOrganisation(User user, String ownerHandle, String name,
		Repository.Visibility visibility, String description)
	{
		Organisation org = organisations.find(ownerHandle)
			.orElseThrow(() -> new ForbiddenOperationException("No such organisation"));
		boolean orgOwner = organisations.roleOf(user, org)
			.filter(role -> role == OrganisationMember.Role.OWNER).isPresent();
		if (!orgOwner)
		{
			throw new ForbiddenOperationException("Only organisation owners can create repositories there");
		}
		return service.create(org, name, visibility, description);
	}

}
