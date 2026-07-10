package de.workaround.account;

import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Validates and assigns a user's URL-safe handle. Used both during onboarding and when an existing
 * user renames in settings. The handle is the path segment in every repo/SSH/federation URL, so it
 * must match a strict charset and be unique across users AND organisations (shared namespace, see
 * {@link HandleService}).
 */
@ApplicationScoped
public class UsernameService
{
	@Inject
	User.Repo users;

	@Inject
	HandleService handles;

	@Transactional
	public void choose(User user, String handle)
	{
		String candidate = handle == null ? "" : handle.trim();
		if (!HandleService.valid(candidate))
		{
			throw new InvalidUsernameException(
				"Username must be 1-39 characters, lowercase letters, digits or hyphens, and start with a letter or digit.");
		}
		if (handles.takenByOther(candidate, user.id))
		{
			throw new UsernameTakenException("That username is already taken.");
		}
		User managed = users.findById(user.id);
		managed.username = candidate;
		user.username = candidate;
	}

	@Transactional
	public void setDisplayName(User user, String displayName)
	{
		if (displayName == null || displayName.isBlank())
		{
			return;
		}
		User managed = users.findById(user.id);
		managed.displayName = displayName;
		user.displayName = displayName;
	}

}
