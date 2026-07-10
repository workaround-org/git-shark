package de.workaround.web;

import java.net.URI;
import java.util.List;

import de.workaround.account.CurrentUser;
import de.workaround.account.InvalidOrganisationException;
import de.workaround.account.OrganisationService;
import de.workaround.git.AccessPolicy;
import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Organisation;
import de.workaround.model.OrganisationMember;
import de.workaround.model.Repository;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Organisation pages: creation (any logged-in user), the public org page with a
 * visibility-filtered repository list, and owner-only member management. Org repositories
 * themselves live under the same /repos/{owner}/{name} routes as personal ones.
 */
@Path("/orgs")
@Produces(MediaType.TEXT_HTML)
public class OrganisationResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance newOrg(String error);

		static native TemplateInstance org(Organisation org, List<Repository> repos, boolean owner);

		static native TemplateInstance members(Organisation org, List<OrganisationMember> members,
			List<OrganisationMember.Role> roles, String error);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	OrganisationService organisations;

	@Inject
	GitRepositoryService repositories;

	@Inject
	AccessPolicy accessPolicy;

	@GET
	@Path("new")
	public TemplateInstance newOrg()
	{
		currentUser.require();
		return Templates.newOrg(null);
	}

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response create(@FormParam("name") String name, @FormParam("displayName") String displayName)
	{
		User user = currentUser.require();
		try
		{
			Organisation org = organisations.create(user, name, displayName);
			return Response.seeOther(URI.create("/orgs/" + org.name)).build();
		}
		catch (InvalidOrganisationException e)
		{
			return Response.status(Response.Status.BAD_REQUEST).entity(Templates.newOrg(e.getMessage())).build();
		}
	}

	@GET
	@Path("{name}")
	public TemplateInstance page(@PathParam("name") String name)
	{
		Organisation org = requireOrg(name);
		User user = currentUser.get();
		List<Repository> visible = repositories.listOwnedBy(org).stream()
			.filter(repo -> accessPolicy.canRead(user, repo))
			.toList();
		return Templates.org(org, visible, isOwner(user, org));
	}

	@GET
	@Path("{name}/members")
	public TemplateInstance members(@PathParam("name") String name)
	{
		Organisation org = requireOwned(name);
		return membersPage(org, null);
	}

	@POST
	@Path("{name}/members")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response addMember(@PathParam("name") String name, @FormParam("username") String username,
		@FormParam("role") String role)
	{
		Organisation org = requireOwned(name);
		try
		{
			organisations.addMember(currentUser.require(), org, username, parseRole(role));
			return backToMembers(org);
		}
		catch (InvalidOrganisationException e)
		{
			return Response.status(Response.Status.BAD_REQUEST).entity(membersPage(org, e.getMessage())).build();
		}
	}

	@POST
	@Path("{name}/members/{username}/role")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response changeRole(@PathParam("name") String name, @PathParam("username") String username,
		@FormParam("role") String role)
	{
		Organisation org = requireOwned(name);
		try
		{
			organisations.changeRole(currentUser.require(), org, username, parseRole(role));
			return backToMembers(org);
		}
		catch (InvalidOrganisationException e)
		{
			return Response.status(Response.Status.BAD_REQUEST).entity(membersPage(org, e.getMessage())).build();
		}
	}

	@POST
	@Path("{name}/members/{username}/remove")
	public Response removeMember(@PathParam("name") String name, @PathParam("username") String username)
	{
		Organisation org = requireOwned(name);
		try
		{
			organisations.removeMember(currentUser.require(), org, username);
			return backToMembers(org);
		}
		catch (InvalidOrganisationException e)
		{
			return Response.status(Response.Status.BAD_REQUEST).entity(membersPage(org, e.getMessage())).build();
		}
	}

	@POST
	@Path("{name}/delete")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response delete(@PathParam("name") String name, @FormParam("confirm") String confirm)
	{
		Organisation org = requireOwned(name);
		if (!org.name.equals(confirm))
		{
			return Response.status(Response.Status.BAD_REQUEST)
				.entity("Confirmation does not match the organisation name").build();
		}
		try
		{
			organisations.delete(currentUser.require(), org);
			return Response.seeOther(URI.create("/")).build();
		}
		catch (InvalidOrganisationException e)
		{
			return Response.status(Response.Status.BAD_REQUEST).entity(membersPage(org, e.getMessage())).build();
		}
	}

	private TemplateInstance membersPage(Organisation org, String error)
	{
		return Templates.members(org, organisations.members(org), List.of(OrganisationMember.Role.values()), error);
	}

	private static Response backToMembers(Organisation org)
	{
		return Response.seeOther(URI.create("/orgs/" + org.name + "/members")).build();
	}

	private static OrganisationMember.Role parseRole(String role)
	{
		try
		{
			return OrganisationMember.Role.valueOf(role == null ? "" : role.trim().toUpperCase());
		}
		catch (IllegalArgumentException e)
		{
			throw new InvalidOrganisationException("Unknown role.");
		}
	}

	private Organisation requireOrg(String name)
	{
		return organisations.find(name).orElseThrow(NotFoundException::new);
	}

	private Organisation requireOwned(String name)
	{
		Organisation org = requireOrg(name);
		if (!isOwner(currentUser.get(), org))
		{
			throw new ForbiddenOperationException("Only an organisation owner can do that");
		}
		return org;
	}

	private boolean isOwner(User user, Organisation org)
	{
		return user != null && user.id != null && organisations.roleOf(user, org)
			.filter(role -> role == OrganisationMember.Role.OWNER).isPresent();
	}

}
