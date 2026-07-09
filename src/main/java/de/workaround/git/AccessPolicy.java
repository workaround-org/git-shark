package de.workaround.git;

import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Single authorization rule set shared by HTTP, SSH and UI: the owner has full access, collaborators
 * read and write repositories they were added to, public repositories are world-readable, private
 * repositories are visible to the owner and collaborators only.
 */
@ApplicationScoped
public class AccessPolicy
{
	private final CollaboratorLookup collaborators;

	@Inject
	public AccessPolicy(CollaboratorLookup collaborators)
	{
		this.collaborators = collaborators;
	}

	public boolean canRead(User user, Repository repository)
	{
		return repository.visibility == Repository.Visibility.PUBLIC || isOwner(user, repository)
			|| isCollaborator(user, repository);
	}

	public boolean canWrite(User user, Repository repository)
	{
		return isOwner(user, repository) || isCollaborator(user, repository);
	}

	private static boolean isOwner(User user, Repository repository)
	{
		return user != null && user.id != null && user.id.equals(repository.owner.id);
	}

	private boolean isCollaborator(User user, Repository repository)
	{
		return user != null && user.id != null && collaborators.isCollaborator(user, repository);
	}

	/** How the policy asks whether a user holds a collaborator grant; implemented by {@link CollaboratorService}. */
	public interface CollaboratorLookup
	{
		boolean isCollaborator(User user, Repository repository);
	}

}
