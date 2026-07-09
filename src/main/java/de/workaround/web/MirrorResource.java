package de.workaround.web;

import java.net.URI;
import java.util.UUID;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.mirror.InvalidMirrorUrlException;
import de.workaround.mirror.MirrorService;
import de.workaround.model.PushMirror;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Owner-only push-mirror management for a repository. Non-owners get 404 on every endpoint —
 * mirrors (and their existence) are never revealed. Validation errors surface as plain 400s; the
 * created secret is accepted here once and never rendered back.
 */
@Path("/repos/{owner}/{name}/mirrors")
public class MirrorResource
{
	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService service;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	MirrorService mirrorService;

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response create(@PathParam("owner") String owner, @PathParam("name") String name,
		@FormParam("url") String url, @FormParam("authType") String authType,
		@FormParam("username") String username, @FormParam("secret") String secret)
	{
		Owned owned = requireOwned(owner, name);
		PushMirror.AuthType type;
		try
		{
			type = PushMirror.AuthType.valueOf(authType == null ? "" : authType);
		}
		catch (IllegalArgumentException e)
		{
			return badRequest("Unknown mirror auth type");
		}
		try
		{
			mirrorService.create(owned.user(), owned.repository(), url, type, username, secret);
		}
		catch (InvalidMirrorUrlException | IllegalArgumentException | IllegalStateException e)
		{
			return badRequest(e.getMessage());
		}
		return backToSettings(owner, name);
	}

	@POST
	@Path("{mirrorId}/delete")
	public Response delete(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("mirrorId") UUID mirrorId)
	{
		Owned owned = requireOwned(owner, name);
		mirrorService.delete(owned.user(), owned.repository(), mirrorId);
		return backToSettings(owner, name);
	}

	@POST
	@Path("{mirrorId}/push")
	public Response pushNow(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("mirrorId") UUID mirrorId)
	{
		Owned owned = requireOwned(owner, name);
		mirrorService.pushNow(owned.user(), owned.repository(), mirrorId);
		return backToSettings(owner, name);
	}

	private record Owned(User user, Repository repository)
	{
	}

	private Owned requireOwned(String owner, String name)
	{
		Repository repository = service.find(owner, name).orElseThrow(NotFoundException::new);
		User user = currentUser.get();
		if (!accessPolicy.canWrite(user, repository))
		{
			// hide the mirror endpoints (and private repositories) from non-owners
			throw new NotFoundException();
		}
		return new Owned(user, repository);
	}

	private static Response backToSettings(String owner, String name)
	{
		return Response.seeOther(URI.create("/repos/" + owner + "/" + name + "/settings")).build();
	}

	private static Response badRequest(String message)
	{
		return Response.status(Response.Status.BAD_REQUEST).entity(message).build();
	}

}
