package de.workaround.web;

import java.net.URI;
import java.net.URISyntaxException;
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
import jakarta.ws.rs.QueryParam;
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
			List<DashboardRepo> repositories, List<OrganisationMember> orgs, User user);

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
		return Templates.dashboard(pinned, notifications.notificationsFor(user), rows,
			organisations.membershipsOf(user), user);
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
	public Response login(@QueryParam("redirect") String redirect)
	{
		// Path is protected (authenticated); OIDC intercepts anonymous access, runs the code flow
		// and restores this URI afterwards (restore-path-after-redirect keeps the redirect
		// parameter alive). Send the user back to the page they came from — local paths only,
		// anything else would be an open redirect.
		currentUser.require();
		return Response.seeOther(localRedirectTarget(redirect)).build();
	}

	static URI localRedirectTarget(String redirect)
	{
		// Local paths only: no protocol-relative ("//", "/\") targets and no control characters
		// (browsers strip e.g. tabs before parsing, which could re-open the protocol-relative hole).
		if (redirect == null || !redirect.startsWith("/")
			|| redirect.startsWith("//") || redirect.startsWith("/\\")
			|| redirect.chars().anyMatch(c -> c < 0x20))
		{
			return URI.create("/");
		}
		try
		{
			// Re-encode path and query: the query parameter arrives decoded, and the multi-arg
			// URI constructor cannot smuggle in a scheme or authority.
			int queryStart = redirect.indexOf('?');
			String path = queryStart < 0 ? redirect : redirect.substring(0, queryStart);
			String query = queryStart < 0 ? null : redirect.substring(queryStart + 1);
			return new URI(null, null, path, query, null);
		}
		catch (URISyntaxException e)
		{
			return URI.create("/");
		}
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
