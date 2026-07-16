package de.workaround.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import de.workaround.account.CurrentUser;
import de.workaround.federation.RemoteFollowService;
import de.workaround.model.ReceivedPush;
import de.workaround.model.RemoteFollow;
import de.workaround.model.RemoteUserFollow;
import de.workaround.model.User;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Follows on remote instances: follow a whole remote user (fanning out to their public repositories,
 * shown grouped) or a single repository by handle/URL, list them with their received-push feed, and
 * unfollow either.
 */
@Path("/following")
@Produces(MediaType.TEXT_HTML)
public class FollowingResource
{
	/** A followed remote user together with the repository follows fanned out from it. */
	public record UserGroup(RemoteUserFollow follow, List<RemoteFollow> repositories)
	{
	}

	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance following(List<UserGroup> users, List<RemoteFollow> follows,
			List<ReceivedPush> pushes, String error);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	RemoteFollowService service;

	@GET
	public TemplateInstance list()
	{
		return render(currentUser.require(), null);
	}

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response follow(@FormParam("handle") String handle)
	{
		User user = currentUser.require();
		try
		{
			service.follow(user, handle);
			return Response.seeOther(URI.create("/following")).build();
		}
		catch (RemoteFollowService.RemoteFollowException e)
		{
			return Response.status(Response.Status.BAD_REQUEST).entity(render(user, e.getMessage())).build();
		}
	}

	@POST
	@Path("users")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response followUser(@FormParam("handle") String handle)
	{
		User user = currentUser.require();
		try
		{
			service.followUser(user, handle);
			return Response.seeOther(URI.create("/following")).build();
		}
		catch (RemoteFollowService.RemoteFollowException e)
		{
			return Response.status(Response.Status.BAD_REQUEST).entity(render(user, e.getMessage())).build();
		}
	}

	@POST
	@Path("{id}/unfollow")
	public Response unfollow(@PathParam("id") UUID id)
	{
		service.unfollow(currentUser.require(), id);
		return Response.seeOther(URI.create("/following")).build();
	}

	@POST
	@Path("users/{id}/unfollow")
	public Response unfollowUser(@PathParam("id") UUID id)
	{
		service.unfollowUser(currentUser.require(), id);
		return Response.seeOther(URI.create("/following")).build();
	}

	private TemplateInstance render(User user, String error)
	{
		List<UserGroup> users = service.listUsers(user).stream()
			.map(uf -> new UserGroup(uf, service.repositoriesOfFollowedUser(user, uf.remoteUserActorId)))
			.toList();
		return Templates.following(users, service.standaloneRepositoryFollows(user),
			service.recentPushes(user), error);
	}

}
