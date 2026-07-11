package de.workaround.dev;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitBrowseService;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.IssueService;
import de.workaround.git.MergeRequestCommentService;
import de.workaround.model.Issue;
import de.workaround.model.MergeRequest;
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

	@Inject
	MergeRequest.Repo mergeRequests;

	@Inject
	MergeRequestCommentService comments;

	@Inject
	IssueService issues;

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

	@Test
	void seedsOneOpenMergeRequestWithACommentAndIsIdempotent()
	{
		seeder.seed();
		Repository demo = repositories.find("alice", "demo").orElseThrow();

		List<MergeRequest> afterFirst = mergeRequests.findByRepository(demo);
		assertEquals(1, afterFirst.size(), "exactly one demo merge request must be seeded");
		MergeRequest mr = afterFirst.get(0);
		assertEquals("feature", mr.sourceBranch);
		assertEquals("main", mr.targetBranch);
		assertEquals(MergeRequest.Status.OPEN, mr.status);
		assertEquals(1, comments.list(mr).size(), "the demo merge request must carry one sample comment");

		int featureCommits = browse.commitCount(repositories.repositoryPath(demo), "feature");

		// re-seeding must not duplicate the branch commit, the merge request, or the comment
		seeder.seed();
		assertEquals(1, mergeRequests.findByRepository(demo).size(), "re-seeding must not add a second merge request");
		assertEquals(1, comments.list(mr).size(), "re-seeding must not add a duplicate comment");
		assertEquals(featureCommits, browse.commitCount(repositories.repositoryPath(demo), "feature"),
			"re-seeding must not add duplicate commits to the feature branch");
	}

	@Test
	void seedsAReadmeWithRicherMarkdown()
	{
		seeder.seed();
		Repository demo = repositories.find("alice", "demo").orElseThrow();

		GitBrowseService.BlobView readme = browse.blob(repositories.repositoryPath(demo), "main", "README.md")
			.orElseThrow(() -> new AssertionError("README.md missing on main"));
		String content = new String(readme.content(), StandardCharsets.UTF_8);
		assertTrue(content.contains("## "), "README must contain markdown section headings");
		assertTrue(content.contains("```"), "README must contain a fenced code block");
		assertTrue(content.contains("- "), "README must contain a list");
	}

	@Test
	void seedsIssuesWithVariedMarkdownDescriptionsAndIsIdempotent()
	{
		seeder.seed();
		Repository demo = repositories.find("alice", "demo").orElseThrow();

		List<Issue> seeded = issues.list(demo);
		assertEquals(4, seeded.size(), "four demo issues must be seeded");
		assertTrue(seeded.stream().anyMatch(i -> i.description != null && i.description.length() > 400),
			"one issue must carry a long markdown description");
		assertTrue(seeded.stream().anyMatch(i -> i.description != null && i.description.length() < 120),
			"one issue must carry a short description");
		assertTrue(seeded.stream().anyMatch(i -> i.description != null && i.description.contains("```")),
			"one issue description must contain a fenced code block");
		assertTrue(seeded.stream().anyMatch(i -> i.status == Issue.Status.DONE),
			"one issue must already be done so status filters are demoable");
		assertTrue(seeded.stream().anyMatch(i -> i.status == Issue.Status.IN_DEVELOPMENT),
			"one issue must be in development");

		seeder.seed();
		assertEquals(4, issues.list(demo).size(), "re-seeding must not duplicate issues");
	}

}
