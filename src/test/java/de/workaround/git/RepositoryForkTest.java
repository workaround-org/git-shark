package de.workaround.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RepositoryForkTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	GitBrowseService browse;

	@Test
	@TestTransaction
	void forkCopiesAllRefsAndRecordsParent() throws Exception
	{
		User owner = persistUser();
		User forker = persistUser();
		Repository source = service.create(owner, "src", Repository.Visibility.PUBLIC, "the original");
		seedWithBranchAndTag(service.repositoryPath(source));

		Repository fork = service.fork(forker, source);

		assertEquals(source.id, fork.parent.id, "fork records its parent");
		assertEquals(forker.id, fork.ownerUser.id, "fork is owned by the forking user");
		assertEquals(source.visibility, fork.visibility, "fork inherits source visibility");
		assertEquals("src", fork.name);

		Path forkPath = service.repositoryPath(fork);
		assertTrue(Files.exists(forkPath.resolve("HEAD")), "fork bare repo exists on disk");
		List<String> branches = browse.branches(forkPath).stream().map(GitBrowseService.BranchInfo::name).toList();
		assertTrue(branches.contains("main"), "main branch copied");
		assertTrue(branches.contains("feature"), "feature branch copied");
		assertTrue(browse.tags(forkPath).contains("v1"), "tag copied");
	}

	@Test
	@TestTransaction
	void cannotForkAPrivateRepositoryYouCannotRead()
	{
		User owner = persistUser();
		User stranger = persistUser();
		Repository priv = service.create(owner, "secret", Repository.Visibility.PRIVATE, null);

		assertThrows(ForbiddenOperationException.class, () -> service.fork(stranger, priv));
	}

	@Test
	@TestTransaction
	void forkingTheSameRepositoryTwiceConflicts()
	{
		User owner = persistUser();
		User forker = persistUser();
		Repository source = service.create(owner, "dup", Repository.Visibility.PUBLIC, null);
		service.fork(forker, source);

		assertThrows(RepositoryAlreadyExistsException.class, () -> service.fork(forker, source));
	}

	private static void seedWithBranchAndTag(Path barePath) throws Exception
	{
		GitTestSeeder.seed(barePath, Map.of("README.md", "hi".getBytes()));
		Path work = Files.createTempDirectory("fork-seed");
		try (Git git = Git.cloneRepository().setURI(barePath.toUri().toString()).setDirectory(work.toFile()).call())
		{
			git.checkout().setCreateBranch(true).setName("feature").call();
			Files.writeString(work.resolve("feature.txt"), "feature work\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("feature commit").setSign(false)
				.setAuthor("seed", "seed@example.com").setCommitter("seed", "seed@example.com").call();
			git.tag().setName("v1").call();
			git.push().setRefSpecs(new RefSpec("refs/heads/feature:refs/heads/feature")).setPushTags().call();
		}
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
