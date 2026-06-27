package de.workaround.account;

import java.net.URI;

import io.quarkus.oidc.OidcSession;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Local logout. kanidm advertises no end_session_endpoint, so Quarkus RP-Initiated Logout is disabled
 * (no quarkus.oidc.logout.path). This endpoint clears the local OIDC session cookie and redirects home,
 * which is the most logout the provider allows.
 */
@Path("/logout")
public class LogoutResource
{
	@Inject
	OidcSession oidcSession;

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Uni<Response> logout()
	{
		return oidcSession.logout().replaceWith(Response.seeOther(URI.create("/")).build());
	}
}
