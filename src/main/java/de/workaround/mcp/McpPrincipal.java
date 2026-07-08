package de.workaround.mcp;

import de.workaround.http.AccessTokenService;
import de.workaround.model.User;
import io.quarkiverse.mcp.server.ToolCallException;
import io.vertx.core.http.HttpServerRequest;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * Resolves the user an MCP tool call acts as, from the {@code Authorization: Bearer <token>} header of the
 * Streamable-HTTP request, where the token is a personal access token (the same secret used for the REST API
 * and Git-over-HTTP). Mirrors the REST {@code ApiPrincipal}: tools apply the same read/write authorization
 * rules as the UI and REST surface. Request-scoped, so it is only usable from HTTP-transport tool calls.
 */
@RequestScoped
public class McpPrincipal
{
	private static final String BEARER = "Bearer ";

	@Inject
	HttpServerRequest request;

	@Inject
	AccessTokenService tokens;

	private boolean resolved;
	private User user;

	/** The authenticated user, or {@code null} when the call carried no valid token. */
	public User orNull()
	{
		if (!resolved)
		{
			user = resolve();
			resolved = true;
		}
		return user;
	}

	/** The authenticated user, or a {@link ToolCallException} (failed tool response) when no valid token was sent. */
	public User require()
	{
		User current = orNull();
		if (current == null)
		{
			throw new ToolCallException("A valid personal access token is required (Authorization: Bearer)");
		}
		return current;
	}

	private User resolve()
	{
		String header = request.getHeader("Authorization");
		if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length()))
		{
			return null;
		}
		return tokens.authenticate(header.substring(BEARER.length()).trim()).orElse(null);
	}
}
