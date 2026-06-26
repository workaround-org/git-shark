package de.workaround.dev;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitBrowseService;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class DevDataSeederTest
{
	@Inject
	DevDataSeeder seeder;

	@Inject
	GitRepositoryService repositories;

	@Inject
	GitBrowseService browse;

	@Test
	void seedsAliceWithDemoRepoContainingACommit()
	{
		seeder.seed();

		Repository demo = repositories.find("alice", "demo").orElseThrow(() -> new AssertionError("alice/demo missing"));
		assertEquals(Repository.Visibility.PUBLIC, demo.visibility);

		Path bare = repositories.repositoryPath(demo);
		assertFalse(browse.isEmpty(bare), "demo repository must have commits");
		assertTrue(browse.commitCount(bare, "main") >= 1, "demo repository must contain at least one commit");
	}

	@Test
	void seedingIsIdempotent()
	{
		seeder.seed();
		Path firstBare = repositories.repositoryPath(repositories.find("alice", "demo").orElseThrow());
		int commitsAfterFirst = browse.commitCount(firstBare, "main");

		seeder.seed();
		Repository demo = repositories.find("alice", "demo").orElseThrow();
		assertEquals(commitsAfterFirst, browse.commitCount(repositories.repositoryPath(demo), "main"),
			"re-seeding must not add duplicate commits or repositories");
	}

}
