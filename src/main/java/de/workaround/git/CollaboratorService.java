package de.workaround.git;

import java.util.List;

import de.workaround.model.Repository;
import de.workaround.model.RepositoryCollaborator;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Manages the flat collaborator role on a repository: the owner grants and revokes read+write
 * access for other local users by username. Also serves as the {@link AccessPolicy}'s collaborator
 * lookup, so HTTP, SSH and UI authorization all see the same grants.
 */
@ApplicationScoped
public class CollaboratorService implements AccessPolicy.CollaboratorLookup
{
	@Inject
	RepositoryCollaborator.Repo collaborators;

	@Inject
	User.Repo users;

	@Transactional
	public RepositoryCollaborator add(User actor, Repository repository, String username)
	{
		requireOwner(actor, repository);
		User user = users.findByUsername(username == null ? "" : username.strip())
			.orElseThrow(() -> new InvalidCollaboratorException("No user with that username exists."));
		if (user.id.equals(repository.owner.id))
		{
			throw new InvalidCollaboratorException("The owner cannot be added as a collaborator.");
		}
		if (collaborators.findByRepositoryAndUser(repository, user).isPresent())
		{
			throw new InvalidCollaboratorException("That user is already a collaborator.");
		}
		RepositoryCollaborator collaborator = new RepositoryCollaborator();
		collaborator.repository = repository;
		collaborator.user = user;
		collaborator.persist();
		return collaborator;
	}

	@Transactional
	public void remove(User actor, Repository repository, String username)
	{
		requireOwner(actor, repository);
		users.findByUsername(username == null ? "" : username.strip())
			.flatMap(user -> collaborators.findByRepositoryAndUser(repository, user))
			.ifPresent(collaborator -> collaborators.deleteById(collaborator.id));
	}

	public List<RepositoryCollaborator> list(Repository repository)
	{
		return collaborators.findByRepository(repository);
	}

	@Override
	public boolean isCollaborator(User user, Repository repository)
	{
		return collaborators.findByRepositoryAndUser(repository, user).isPresent();
	}

	private static void requireOwner(User actor, Repository repository)
	{
		if (actor == null || actor.id == null || !actor.id.equals(repository.owner.id))
		{
			throw new ForbiddenOperationException("Only the repository owner can manage collaborators");
		}
	}

}
