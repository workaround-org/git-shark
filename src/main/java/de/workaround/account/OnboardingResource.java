package de.workaround.account;

import java.net.URI;

import de.workaround.model.User;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * One-time first-login page where a user picks a URL-safe handle. The OnboardingFilter forces every
 * authenticated request here until a handle is set.
 */
@Path("/onboarding")
@Produces(MediaType.TEXT_HTML)
public class OnboardingResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance onboarding(String suggestedHandle, String displayName, String error);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	SecurityIdentity identity;

	@Inject
	UsernameService usernames;

	@GET
	public Response onboarding()
	{
		User user = currentUser.require();
		if (user.username != null && !user.username.isBlank())
		{
			return Response.seeOther(URI.create("/")).build();
		}
		return Response.ok(Templates.onboarding(suggestedHandle(), user.displayName, null)).build();
	}

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response submit(@FormParam("username") String username, @FormParam("displayName") String displayName)
	{
		User user = currentUser.require();
		try
		{
			usernames.choose(user, username);
			usernames.setDisplayName(user, displayName);
			return Response.seeOther(URI.create("/")).build();
		}
		catch (InvalidUsernameException | UsernameTakenException e)
		{
			return Response.status(Response.Status.BAD_REQUEST)
				.entity(Templates.onboarding(username, displayName, e.getMessage()))
				.build();
		}
	}

	private String suggestedHandle()
	{
		if (identity.getPrincipal() instanceof JsonWebToken jwt)
		{
			return HandleSuggester.suggest(jwt.getClaim("preferred_username"));
		}
		return "";
	}

}
