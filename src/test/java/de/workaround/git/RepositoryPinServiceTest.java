package de.workaround.git;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.Repository;
import de.workaround.model.RepositoryPin;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RepositoryPinServiceTest
{
	@Inject
	RepositoryPinService pinService;

	@Inject
	RepositoryPin.Repo pins;

	@Inject
	Repository.Repo repositories;

	@Inject
	User.Repo users;

	@Inject
	EntityManager em;

	@Test
	@TestTransaction
	void pinIsIdempotentAndUnpinRemovesIt()
	{
		User alice = persistUser("pin-alice");
		Repository repo = persistRepo(alice, "p1");

		pinService.pin(alice, repo);
		pinService.pin(alice, repo);

		assertEquals(1, pins.findByUser(alice).size(), "pinning twice must not create a duplicate");
		assertEquals(1, pinService.listPinned(alice).size());
		assertTrue(pinService.isPinned(alice, repo));

		pinService.unpin(alice, repo);

		assertFalse(pinService.isPinned(alice, repo));
		assertTrue(pinService.listPinned(alice).isEmpty());
	}

	@Test
	@TestTransaction
	void deletingARepositoryRemovesItsPins()
	{
		User bob = persistUser("pin-bob");
		Repository repo = persistRepo(bob, "p2");
		pinService.pin(bob, repo);

		// Flush + detach so the still-managed pin doesn't trip Hibernate's own integrity check;
		// deleting the repository must then drop the pin via the DB-level ON DELETE CASCADE.
		em.flush();
		em.clear();
		repositories.deleteById(repo.id);
		em.flush();

		assertTrue(pinService.listPinned(bob).isEmpty(), "pins must be cascade-deleted with the repository");
	}

	@Test
	@TestTransaction
	void pinsAreIsolatedPerUser()
	{
		User carol = persistUser("pin-carol");
		User dave = persistUser("pin-dave");
		Repository shared = persistRepo(carol, "shared");

		pinService.pin(carol, shared);

		assertEquals(1, pinService.listPinned(carol).size());
		assertTrue(pinService.listPinned(dave).isEmpty(), "dave must not see carol's pins");
	}

	private Repository persistRepo(User owner, String name)
	{
		Repository repo = new Repository();
		repo.name = name;
		repo.owner = owner;
		repo.visibility = Repository.Visibility.PUBLIC;
		repo.persist();
		return repo;
	}

	private User persistUser(String name)
	{
		User user = new User();
		user.oidcSub = name + "-" + UUID.randomUUID();
		user.username = name + "-" + UUID.randomUUID().toString().substring(0, 8);
		user.persist();
		return user;
	}

}
