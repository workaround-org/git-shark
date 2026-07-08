package de.workaround.account;

import de.workaround.model.User;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Resolves the logged-in UI user, provisioning the local record from token claims on access.
 * Returns null for anonymous requests. Named so templates can switch the header nav via
 * {cdi:currentUser.loggedIn} without each page passing the user along.
 */
@Named("currentUser")
@RequestScoped
public class CurrentUser
{
	@Inject
	SecurityIdentity identity;

	@Inject
	UserProvisioningService provisioning;

	private User cached;

	/** Cheap auth check for templates — no user provisioning, just the identity state. */
	public boolean isLoggedIn()
	{
		return !identity.isAnonymous();
	}

	public User get()
	{
		if (identity.isAnonymous())
		{
			return null;
		}
		if (cached == null)
		{
			cached = provision();
		}
		return cached;
	}

	public User require()
	{
		User user = get();
		if (user == null)
		{
			throw new de.workaround.git.ForbiddenOperationException("Authentication required");
		}
		return user;
	}

	private User provision()
	{
		String name = identity.getPrincipal().getName();
		if (identity.getPrincipal() instanceof JsonWebToken jwt)
		{
			String sub = jwt.getSubject() != null ? jwt.getSubject() : name;
			// The handle is no longer taken from preferred_username: a new user picks a URL-safe one
			// on the onboarding page. The claim is passed only for the dev adopt-username path.
			return provisioning.provisionFromOidc(sub, jwt.getClaim("preferred_username"), jwt.getClaim("name"),
				jwt.getClaim("email"));
		}
		return provisioning.provision(name, name, null, null);
	}

}
