package de.workaround.account;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import de.workaround.http.AccessTokenService;
import de.workaround.model.AccessToken;
import de.workaround.model.SshKey;
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

@Path("/settings")
@Produces(MediaType.TEXT_HTML)
public class SettingsResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance keys(List<SshKey> keys, String error);

		static native TemplateInstance tokens(List<AccessToken> tokens);

		static native TemplateInstance tokenCreated(String plaintext);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	SshKeyService keyService;

	@Inject
	AccessTokenService tokenService;

	@GET
	@Path("/keys")
	public TemplateInstance keys()
	{
		return Templates.keys(keyService.list(currentUser.require()), null);
	}

	@POST
	@Path("/keys")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response addKey(@FormParam("title") String title, @FormParam("key") String key)
	{
		try
		{
			keyService.add(currentUser.require(), title, key);
			return Response.seeOther(URI.create("/settings/keys")).build();
		}
		catch (InvalidSshKeyException | DuplicateSshKeyException e)
		{
			return Response.status(Response.Status.BAD_REQUEST)
				.entity(Templates.keys(keyService.list(currentUser.require()), e.getMessage()))
				.build();
		}
	}

	@POST
	@Path("/keys/{id}/delete")
	public Response removeKey(@PathParam("id") UUID id)
	{
		keyService.remove(currentUser.require(), id);
		return Response.seeOther(URI.create("/settings/keys")).build();
	}

	@GET
	@Path("/tokens")
	public TemplateInstance tokens()
	{
		return Templates.tokens(tokenService.list(currentUser.require()));
	}

	@POST
	@Path("/tokens")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public TemplateInstance createToken(@FormParam("label") String label)
	{
		AccessTokenService.CreatedToken created = tokenService.create(currentUser.require(), label);
		return Templates.tokenCreated(created.plaintext());
	}

	@POST
	@Path("/tokens/{id}/revoke")
	public Response revokeToken(@PathParam("id") UUID id)
	{
		tokenService.revoke(currentUser.require(), id);
		return Response.seeOther(URI.create("/settings/tokens")).build();
	}

}
