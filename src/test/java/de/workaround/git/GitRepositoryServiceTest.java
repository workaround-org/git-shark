package de.workaround.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GitRepositoryServiceTest
{
	@Inject
	GitRepositoryService service;

	@Test
	@TestTransaction
	void createInitializesBareRepoAndPersistsMetadata()
	{
		User owner = persistUser();

		Repository repo = service.create(owner, "project", Repository.Visibility.PUBLIC, "demo");

		assertNotNull(repo.id);
		Path path = service.repositoryPath(repo);
		assertTrue(Files.isDirectory(path), "bare repository directory must exist");
		assertTrue(Files.exists(path.resolve("HEAD")), "bare repository must have a HEAD file");
		assertTrue(path.getFileName().toString().endsWith(".git"));
	}

	@Test
	@TestTransaction
	void createRejectsDuplicateNamePerOwner()
	{
		User owner = persistUser();
		service.create(owner, "twice", Repository.Visibility.PRIVATE, null);

		assertThrows(RepositoryAlreadyExistsException.class,
			() -> service.create(owner, "twice", Repository.Visibility.PRIVATE, null));
	}

	@Test
	@TestTransaction
	void createRejectsInvalidNames()
	{
		User owner = persistUser();

		for (String invalid : new String[] { "..", ".", "a/b", "evil name", "", "tilde~" })
		{
			assertThrows(InvalidRepositoryNameException.class,
				() -> service.create(owner, invalid, Repository.Visibility.PUBLIC, null), "should reject: " + invalid);
		}
	}

	@Test
	@TestTransaction
	void resolvesExistingRepositoryByOwnerAndName()
	{
		User owner = persistUser();
		Repository created = service.create(owner, "resolvable", Repository.Visibility.PUBLIC, null);

		Repository found = service.find(owner.username, "resolvable").orElseThrow();
		assertEquals(created.id, found.id);
		assertTrue(Files.isDirectory(service.repositoryPath(found)));
	}

	@Test
	@TestTransaction
	void resolveUnknownRepositoryIsEmpty()
	{
		assertTrue(service.find("nobody", "nothing").isEmpty());
	}

	@Test
	@TestTransaction
	void deleteRemovesDatabaseRecordAndDisk()
	{
		User owner = persistUser();
		Repository repo = service.create(owner, "doomed", Repository.Visibility.PUBLIC, null);
		Path path = service.repositoryPath(repo);

		service.delete(owner, repo);

		assertFalse(Files.exists(path), "repository directory must be removed");
		assertTrue(service.find(owner.username, "doomed").isEmpty());
	}

	@Test
	@TestTransaction
	void deleteByNonOwnerIsForbidden()
	{
		User owner = persistUser();
		User stranger = persistUser();
		Repository repo = service.create(owner, "guarded", Repository.Visibility.PUBLIC, null);

		assertThrows(ForbiddenOperationException.class, () -> service.delete(stranger, repo));
		assertTrue(service.find(owner.username, "guarded").isPresent());
	}

	@Test
	@TestTransaction
	void listsOwnAndPublicRepositoriesForUser()
	{
		User alice = persistUser();
		User bob = persistUser();
		Repository alicePrivate = service.create(alice, "alice-private", Repository.Visibility.PRIVATE, null);
		Repository bobPublic = service.create(bob, "bob-public", Repository.Visibility.PUBLIC, null);
		Repository bobPrivate = service.create(bob, "bob-private", Repository.Visibility.PRIVATE, null);

		List<Repository> visible = service.listVisibleTo(alice);

		assertTrue(visible.stream().anyMatch(r -> r.id.equals(alicePrivate.id)), "own private visible");
		assertTrue(visible.stream().anyMatch(r -> r.id.equals(bobPublic.id)), "foreign public visible");
		assertFalse(visible.stream().anyMatch(r -> r.id.equals(bobPrivate.id)), "foreign private hidden");
	}

	@Test
	@TestTransaction
	void anonymousSeesOnlyPublicRepositories()
	{
		User owner = persistUser();
		Repository pub = service.create(owner, "pub", Repository.Visibility.PUBLIC, null);
		Repository priv = service.create(owner, "priv", Repository.Visibility.PRIVATE, null);

		List<Repository> visible = service.listVisibleTo(null);

		assertTrue(visible.stream().anyMatch(r -> r.id.equals(pub.id)));
		assertFalse(visible.stream().anyMatch(r -> r.id.equals(priv.id)));
	}

	private static User persistUser()
	{
		String name = "user-" + UUID.randomUUID();
		User user = new User();
		user.oidcSub = "sub-" + name;
		user.username = name;
		user.persist();
		return user;
	}

}
