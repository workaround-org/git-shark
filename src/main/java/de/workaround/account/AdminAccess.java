package de.workaround.account;

import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import de.workaround.git.ForbiddenOperationException;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Instance-level administration gate. There is no admin role in the schema yet (phase 1): an admin
 * is simply a logged-in user whose handle appears in {@code gitshark.admin.handles}
 * (env {@code GITSHARK_ADMIN_HANDLES}, comma-separated). Empty list means the instance has no admins
 * and every admin-only page is closed.
 */
@ApplicationScoped
public class AdminAccess
{
	@ConfigProperty(name = "gitshark.admin.handles")
	Optional<List<String>> adminHandles;

	public boolean isAdmin(User user)
	{
		if (user == null || user.username == null)
		{
			return false;
		}
		return adminHandles.orElse(List.of()).contains(user.username);
	}

	/** Throw {@link ForbiddenOperationException} unless the user is an instance admin. */
	public void require(User user)
	{
		if (!isAdmin(user))
		{
			throw new ForbiddenOperationException("Instance administrator access required");
		}
	}
}
