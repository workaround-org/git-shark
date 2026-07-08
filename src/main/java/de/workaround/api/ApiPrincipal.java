package de.workaround.api;

import de.workaround.model.User;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.NotAuthorizedException;

/**
 * The user a {@code /api/v1} request authenticated as via its personal access token, or none for
 * anonymous requests. Populated per request by {@link ApiTokenAuthFilter}; resources read it to
 * apply the same read/write authorization rules the UI uses. Distinct from the UI's OIDC-backed
 * {@code CurrentUser}: API clients authenticate with a bearer token, not a browser session.
 */
@RequestScoped
public class ApiPrincipal
{
	private User user;

	void set(User user)
	{
		this.user = user;
	}

	/** The authenticated user, or {@code null} for an anonymous (tokenless) request. */
	public User orNull()
	{
		return user;
	}

	/** The authenticated user, or 401 if the request carried no valid token. */
	public User require()
	{
		if (user == null)
		{
			throw new NotAuthorizedException("Bearer realm=\"git-shark-api\"");
		}
		return user;
	}
}
