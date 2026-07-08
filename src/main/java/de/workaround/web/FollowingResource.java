package de.workaround.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import de.workaround.account.CurrentUser;
import de.workaround.federation.RemoteFollowService;
import de.workaround.model.RemoteFollow;
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

/** Follows of repositories on remote instances: list, follow by handle/URL, unfollow. */
@Path("/following")
@Produces(MediaType.TEXT_HTML)
public class FollowingResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance following(List<RemoteFollow> follows, String error);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	RemoteFollowService service;

	@GET
	public TemplateInstance list()
	{
		return Templates.following(service.list(currentUser.require()), null);
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
			return Response.status(Response.Status.BAD_REQUEST)
				.entity(Templates.following(service.list(user), e.getMessage()))
				.build();
		}
	}

	@POST
	@Path("{id}/unfollow")
	public Response unfollow(@PathParam("id") UUID id)
	{
		service.unfollow(currentUser.require(), id);
		return Response.seeOther(URI.create("/following")).build();
	}

}
