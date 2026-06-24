package de.workaround.git;

import java.util.List;

import de.workaround.model.Repository;
import de.workaround.model.RepositoryPin;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Manages per-user repository pins for the dashboard. Pinning is idempotent and respects the same
 * read-visibility rule as the rest of the platform: you can only pin a repository you can see.
 */
@ApplicationScoped
public class RepositoryPinService
{
	@Inject
	RepositoryPin.Repo pins;

	@Inject
	AccessPolicy accessPolicy;

	@Transactional
	public void pin(User user, Repository repository)
	{
		if (!accessPolicy.canRead(user, repository))
		{
			throw new ForbiddenOperationException("Cannot pin a repository you cannot access");
		}
		if (pins.findByUserAndRepository(user, repository).isPresent())
		{
			return;
		}
		RepositoryPin pin = new RepositoryPin();
		pin.user = user;
		pin.repository = repository;
		pin.persist();
	}

	@Transactional
	public void unpin(User user, Repository repository)
	{
		pins.findByUserAndRepository(user, repository).ifPresent(pin -> pins.deleteById(pin.id));
	}

	public List<Repository> listPinned(User user)
	{
		return pins.findPinnedRepositories(user);
	}

	public boolean isPinned(User user, Repository repository)
	{
		return pins.findByUserAndRepository(user, repository).isPresent();
	}

}
