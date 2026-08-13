package de.workaround.protect;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Test-only stand-in for the Turnstile/hCaptcha {@code siteverify} endpoint: the token
 * {@code good-token} verifies, everything else does not. Lets the challenge flow be exercised
 * end-to-end over the real HTTP client without talking to Cloudflare.
 */
@Path("/test/siteverify")
public class StubSiteverifyResource
{
	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.APPLICATION_JSON)
	public String verify(@FormParam("secret") String secret, @FormParam("response") String response)
	{
		boolean ok = "test-secret-key".equals(secret) && "good-token".equals(response);
		return "{\"success\":" + ok + ",\"error-codes\":[]}";
	}
}
