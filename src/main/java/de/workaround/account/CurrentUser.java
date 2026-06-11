package de.workaround.account;

import de.workaround.model.User;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Resolves the logged-in UI user, provisioning the local record from token claims on access.
 * Returns null for anonymous requests.
 */
@RequestScoped
public class CurrentUser
{
	@Inject
	SecurityIdentity identity;

	@Inject
	UserProvisioningService provisioning;

	private User cached;

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
			String username = firstNonNull(jwt.getClaim("preferred_username"), name, sub);
			return provisioning.provision(sub, username, jwt.getClaim("name"), jwt.getClaim("email"));
		}
		return provisioning.provision(name, name, null, null);
	}

	private static String firstNonNull(String... values)
	{
		for (String value : values)
		{
			if (value != null && !value.isBlank())
			{
				return value;
			}
		}
		return null;
	}

}
