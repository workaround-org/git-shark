package de.workaround.git;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.Repository;
import de.workaround.model.User;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessPolicyTest
{
	private final AccessPolicy policy = new AccessPolicy();

	private final User owner = user();

	private final User stranger = user();

	@Test
	void ownerReadsAndWritesOwnRepository()
	{
		Repository repo = repo(owner, Repository.Visibility.PRIVATE);

		assertTrue(policy.canRead(owner, repo));
		assertTrue(policy.canWrite(owner, repo));
	}

	@Test
	void publicRepositoryIsWorldReadableButNotWritable()
	{
		Repository repo = repo(owner, Repository.Visibility.PUBLIC);

		assertTrue(policy.canRead(stranger, repo));
		assertTrue(policy.canRead(null, repo), "anonymous read on public repo");
		assertFalse(policy.canWrite(stranger, repo));
		assertFalse(policy.canWrite(null, repo), "anonymous never writes");
	}

	@Test
	void privateRepositoryIsOwnerOnly()
	{
		Repository repo = repo(owner, Repository.Visibility.PRIVATE);

		assertFalse(policy.canRead(stranger, repo));
		assertFalse(policy.canRead(null, repo));
		assertFalse(policy.canWrite(stranger, repo));
	}

	private static User user()
	{
		User user = new User();
		user.id = UUID.randomUUID();
		user.username = "u-" + user.id;
		return user;
	}

	private static Repository repo(User owner, Repository.Visibility visibility)
	{
		Repository repo = new Repository();
		repo.id = UUID.randomUUID();
		repo.owner = owner;
		repo.visibility = visibility;
		return repo;
	}

}
