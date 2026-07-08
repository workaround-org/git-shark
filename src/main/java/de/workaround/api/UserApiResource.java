package de.workaround.api;

import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The identity a request authenticated as, under {@code /api/v1/user}. Requires a valid access token
 * (401 otherwise); handy for clients to verify their token and discover their own handle.
 */
@Path("/api/v1/user")
@Produces(MediaType.APPLICATION_JSON)
public class UserApiResource
{
	@Inject
	ApiPrincipal principal;

	@GET
	public ApiModels.UserView current()
	{
		User user = principal.require();
		return new ApiModels.UserView(user.username, user.displayName);
	}
}
