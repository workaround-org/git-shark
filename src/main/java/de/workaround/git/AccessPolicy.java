package de.workaround.git;

import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Single authorization rule set shared by HTTP, SSH and UI: the owner has full access,
 * public repositories are world-readable, private repositories are owner-only.
 */
@ApplicationScoped
public class AccessPolicy
{
	public boolean canRead(User user, Repository repository)
	{
		return repository.visibility == Repository.Visibility.PUBLIC || isOwner(user, repository);
	}

	public boolean canWrite(User user, Repository repository)
	{
		return isOwner(user, repository);
	}

	private static boolean isOwner(User user, Repository repository)
	{
		return user != null && user.id != null && user.id.equals(repository.owner.id);
	}

}
