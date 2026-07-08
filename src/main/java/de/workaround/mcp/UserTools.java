package de.workaround.mcp;

import de.workaround.api.ApiModels;
import de.workaround.model.User;
import io.quarkiverse.mcp.server.Tool;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * MCP tool exposing the identity a call authenticated as, so a client can verify its personal access token
 * and discover its own handle before performing owner-scoped operations.
 */
@Singleton
public class UserTools
{
	@Inject
	McpPrincipal principal;

	@Tool(description = "Return the user the supplied personal access token belongs to. Requires a valid token.")
	public ApiModels.UserView currentUser()
	{
		User user = principal.require();
		return new ApiModels.UserView(user.username, user.displayName);
	}
}
