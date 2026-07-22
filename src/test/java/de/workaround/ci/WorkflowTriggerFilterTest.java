package de.workaround.ci;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
import de.workaround.model.ActionRun;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Ref-based trigger filters (issue #2, phase 2): {@code on.push.branches}/{@code branches-ignore} and
 * {@code tags}/{@code tags-ignore} with GitHub-style globs, plus tag-push handling. A bare
 * {@code on: push} keeps triggering on any branch push (and never on tags).
 */
@QuarkusTest
class WorkflowTriggerFilterTest
{
	@Inject
	WorkflowIngestService ingest;

	@Inject
	GitRepositoryService repositories;

	@Inject
	ActionRun.Repo runs;

	@Test
	void branchesFilterRunsOnlyMatchingBranch()
	{
		String on = "on:\n  push:\n    branches: [main]\n";
		assertEquals(1, runCount("tf-a", "main", "refs/heads/main", on));
		assertEquals(0, runCount("tf-b", "feature", "refs/heads/feature", on));
	}

	@Test
	void branchGlobMatchesNestedName()
	{
		String on = "on:\n  push:\n    branches: ['release/*']\n";
		assertEquals(1, runCount("tf-c", "release/1", "refs/heads/release/1", on));
	}

	@Test
	void branchesIgnoreSkipsListedBranch()
	{
		String on = "on:\n  push:\n    branches-ignore: [main]\n";
		assertEquals(0, runCount("tf-d", "main", "refs/heads/main", on));
		assertEquals(1, runCount("tf-e", "dev", "refs/heads/dev", on));
	}

	@Test
	void bareOnPushRunsOnAnyBranchButNotTags()
	{
		String on = "on: push\n";
		assertEquals(1, runCount("tf-f", "main", "refs/heads/main", on));
		assertEquals(0, runCount("tf-g", "main", "refs/tags/v1", on));
	}

	@Test
	void tagsFilterRunsOnMatchingTagPush()
	{
		String on = "on:\n  push:\n    tags: ['v*']\n";
		assertEquals(1, runCount("tf-h", "main", "refs/tags/v1.2.0", on));
		// a plain branch push does not match a tag-only push filter
		assertEquals(0, runCount("tf-i", "main", "refs/heads/main", on));
	}

	@Test
	void tagPushWithoutTagFilterDoesNotRun()
	{
		// a branches-only block never opts tags in
		String on = "on:\n  push:\n    branches: [main]\n";
		assertEquals(0, runCount("tf-j", "main", "refs/tags/v1", on));
	}

	@Test
	void doubleStarGlobSpansSlashes()
	{
		String on = "on:\n  push:\n    branches: ['release/**']\n";
		assertEquals(1, runCount("tf-k", "release/1/beta", "refs/heads/release/1/beta", on));
		// a single star does not span the extra segment
		String single = "on:\n  push:\n    branches: ['release/*']\n";
		assertEquals(0, runCount("tf-l", "release/1/beta", "refs/heads/release/1/beta", single));
	}

	private int runCount(String repoName, String seedBranch, String pushRef, String onBlock)
	{
		String yaml = onBlock + "jobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo hi\n";
		Repository repo = seedAndIngest(repoName, seedBranch, pushRef, yaml);
		return runs.findByRepository(repo).size();
	}

	@Transactional
	Repository seedAndIngest(String repoName, String seedBranch, String pushRef, String yaml)
	{
		String username = repoName + "-" + UUID.randomUUID().toString().substring(0, 8);
		User owner = new User();
		owner.oidcSub = username;
		owner.username = username;
		owner.persist();
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);

		try
		{
			Path bare = repositories.repositoryPath(repo);
			GitTestSeeder.seedBranch(bare, seedBranch,
				Map.of(".forgejo/workflows/ci.yml", yaml.getBytes(StandardCharsets.UTF_8)));
			try (org.eclipse.jgit.lib.Repository db = new FileRepositoryBuilder().setGitDir(bare.toFile()).build())
			{
				ObjectId head = db.resolve("refs/heads/" + seedBranch);
				ReceiveCommand command = new ReceiveCommand(ObjectId.zeroId(), head, pushRef);
				command.setResult(ReceiveCommand.Result.OK);
				ingest.onPush(repo.ownerHandle(), repo.name, owner.id, db, List.of(command));
			}
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
		return repo;
	}
}
