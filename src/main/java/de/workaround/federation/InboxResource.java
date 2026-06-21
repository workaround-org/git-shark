package de.workaround.federation;

import java.util.function.Function;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Inbox POST endpoints for repository, user, and instance actors. Each delegates to
 * {@link InboxService}, which verifies the HTTP Signature, enforces the allowlist, deduplicates,
 * and dispatches. All endpoints 404 when federation is not operational.
 */
@Path("/ap")
@Consumes({ ActivityPubMedia.ACTIVITY_JSON, ActivityPubMedia.LD_JSON, "application/json" })
public class InboxResource
{
	@Inject
	FederationConfig config;

	@Inject
	InboxService inboxService;

	@Context
	HttpHeaders headers;

	@Context
	UriInfo uriInfo;

	@POST
	@Path("repos/{owner}/{name}/inbox")
	public Response repositoryInbox(byte[] body)
	{
		return handle(body);
	}

	@POST
	@Path("users/{username}/inbox")
	public Response personInbox(byte[] body)
	{
		return handle(body);
	}

	@POST
	@Path("instance/inbox")
	public Response instanceInbox(byte[] body)
	{
		return handle(body);
	}

	private Response handle(byte[] body)
	{
		if (!config.operational())
		{
			throw new NotFoundException();
		}
		// Reconstruct `host` from the request authority: over HTTP/2 there is no classic Host header
		// (it is the :authority pseudo-header), so getHeaderString("host") would be empty.
		String authority = uriInfo.getRequestUri().getAuthority();
		Function<String, String> lookup = name -> "host".equalsIgnoreCase(name) && authority != null
			? authority
			: headers.getHeaderString(name);
		int status = inboxService.receive(body == null ? new byte[0] : body, lookup, "POST",
			uriInfo.getRequestUri().getRawPath());
		return Response.status(status).build();
	}

}
