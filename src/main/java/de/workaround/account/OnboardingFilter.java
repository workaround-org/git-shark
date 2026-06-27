package de.workaround.account;

import java.net.URI;

import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Redirects an authenticated user who has not yet chosen a handle to {@code /onboarding}. Anonymous
 * requests (including git transport, SSH and federation, which use their own non-OIDC auth) are left
 * alone, as are the onboarding page itself and logout.
 */
@Provider
public class OnboardingFilter implements ContainerRequestFilter
{
	@Inject
	CurrentUser currentUser;

	@Override
	public void filter(ContainerRequestContext context)
	{
		String path = context.getUriInfo().getPath();
		if (path.startsWith("/"))
		{
			path = path.substring(1);
		}
		if (path.equals("onboarding") || path.equals("logout"))
		{
			return;
		}
		User user = currentUser.get();
		if (user != null && (user.username == null || user.username.isBlank()))
		{
			context.abortWith(Response.seeOther(URI.create("/onboarding")).build());
		}
	}

}
