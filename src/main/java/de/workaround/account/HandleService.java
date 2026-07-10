package de.workaround.account;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import de.workaround.model.Organisation;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The shared handle namespace: usernames and organisation names are both path segments in
 * repo/SSH/federation URLs, so they follow one charset rule and must be unique across BOTH tables.
 * Each table has its own unique index; the cross-table check here is authoritative and runs inside
 * the caller's transaction ({@link UsernameService}, {@link OrganisationService}).
 */
@ApplicationScoped
public class HandleService
{
	public static final Pattern HANDLE = Pattern.compile("^[a-z0-9][a-z0-9-]{0,38}$");

	@Inject
	User.Repo users;

	@Inject
	Organisation.Repo organisations;

	public static boolean valid(String handle)
	{
		return handle != null && HANDLE.matcher(handle).matches();
	}

	/** True when another user or any organisation already holds the handle. */
	public boolean takenByOther(String handle, UUID exceptUserId)
	{
		Optional<User> user = users.findByUsername(handle);
		if (user.isPresent() && !user.get().id.equals(exceptUserId))
		{
			return true;
		}
		return organisations.findByName(handle).isPresent();
	}

}
