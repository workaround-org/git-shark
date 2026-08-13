package de.workaround.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.workaround.model.Release;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ReleaseServiceTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	ReleaseService releases;

	@Inject
	GitBrowseService browse;

	@Inject
	CollaboratorService collaborators;

	@Inject
	User.Repo users;

	@Test
	void publishesAReleaseForAnExistingTag() throws Exception
	{
		User owner = persistUser("rel-existing");
		Repository repo = seededRepo(owner, "ship");
		GitTestSeeder.seedTag(service.repositoryPath(repo), "v1.0.0");

		Release release = releases.create(owner, repo, "v1.0.0", null, "1.0.0", "First cut", false);

		assertEquals("v1.0.0", release.tagName);
		assertEquals("1.0.0", release.title);
		assertEquals("First cut", release.body);
		assertFalse(release.prerelease);
		assertEquals(owner.id, release.author.id);
		// the tag is resolved to a commit at publish time, so the page can link the exact revision
		assertEquals(40, release.commitId.length());
		assertEquals(List.of("v1.0.0"), releases.list(repo).stream().map(r -> r.tagName).toList());
	}

	@Test
	void creatingWithATargetCreatesTheTag() throws Exception
	{
		User owner = persistUser("rel-newtag");
		Repository repo = seededRepo(owner, "tagme");
		Path bare = service.repositoryPath(repo);
		assertFalse(browse.tags(bare).contains("v2.0.0"));

		releases.create(owner, repo, "v2.0.0", "main", "2.0.0", null, false);

		assertTrue(browse.tags(bare).contains("v2.0.0"));
	}

	@Test
	void rejectsAnUnknownTagWithoutATarget() throws Exception
	{
		User owner = persistUser("rel-notag");
		Repository repo = seededRepo(owner, "missing");

		assertThrows(InvalidReleaseException.class,
			() -> releases.create(owner, repo, "v9.9.9", null, "9.9.9", null, false));
	}

	@Test
	void rejectsASecondReleaseForTheSameTag() throws Exception
	{
		User owner = persistUser("rel-dupe");
		Repository repo = seededRepo(owner, "dupe");
		releases.create(owner, repo, "v1.0.0", "main", "1.0.0", null, false);

		assertThrows(InvalidReleaseException.class,
			() -> releases.create(owner, repo, "v1.0.0", "main", "again", null, false));
	}

	@Test
	void rejectsABlankTagName() throws Exception
	{
		User owner = persistUser("rel-blank");
		Repository repo = seededRepo(owner, "blank");

		assertThrows(InvalidReleaseException.class,
			() -> releases.create(owner, repo, "  ", "main", "1.0.0", null, false));
	}

	@Test
	void aStrangerCannotPublishOrDelete() throws Exception
	{
		User owner = persistUser("rel-owner");
		User stranger = persistUser("rel-stranger");
		Repository repo = seededRepo(owner, "guarded");
		Release release = releases.create(owner, repo, "v1.0.0", "main", "1.0.0", null, false);

		assertThrows(ForbiddenOperationException.class,
			() -> releases.create(stranger, repo, "v1.1.0", "main", "1.1.0", null, false));
		assertThrows(ForbiddenOperationException.class, () -> releases.delete(stranger, release));
	}

	@Test
	void aCollaboratorCanPublish() throws Exception
	{
		User owner = persistUser("rel-collab-owner");
		User collab = persistUser("rel-collab");
		Repository repo = seededRepo(owner, "shared");
		collaborators.add(owner, repo, collab.username);

		Release release = releases.create(collab, repo, "v1.0.0", "main", "1.0.0", null, false);

		assertEquals(collab.id, release.author.id);
	}

	@Test
	void theLatestReleaseSkipsPrereleases() throws Exception
	{
		User owner = persistUser("rel-latest");
		Repository repo = seededRepo(owner, "latest");
		releases.create(owner, repo, "v1.0.0", "main", "1.0.0", null, false);
		releases.create(owner, repo, "v1.1.0-rc1", "main", "1.1.0-rc1", null, true);

		assertEquals("v1.0.0", releases.findLatest(repo).orElseThrow().tagName);
		// the list is newest first, prereleases included
		assertEquals(List.of("v1.1.0-rc1", "v1.0.0"), releases.list(repo).stream().map(r -> r.tagName).toList());
	}

	@Test
	void updatesAndDeletesARelease() throws Exception
	{
		User owner = persistUser("rel-edit");
		Repository repo = seededRepo(owner, "editable");
		Release release = releases.create(owner, repo, "v1.0.0", "main", "1.0.0", "old notes", false);

		releases.update(owner, release, "1.0.0 final", "new notes", true);
		Release reloaded = releases.find(repo, "v1.0.0").orElseThrow();
		assertEquals("1.0.0 final", reloaded.title);
		assertEquals("new notes", reloaded.body);
		assertTrue(reloaded.prerelease);

		releases.delete(owner, reloaded);
		assertTrue(releases.find(repo, "v1.0.0").isEmpty());
		// deleting the release leaves the git tag alone
		assertTrue(browse.tags(service.repositoryPath(repo)).contains("v1.0.0"));
	}

	private Repository seededRepo(User owner, String name) throws Exception
	{
		Repository repo = service.create(owner, name, Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("README.md", "# hi\n".getBytes(StandardCharsets.UTF_8)));
		return repo;
	}

	@Transactional
	User persistUser(String name)
	{
		User existing = users.findByOidcSubOptional(name).orElse(null);
		if (existing != null)
		{
			return existing;
		}
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.persist();
		return user;
	}

}
