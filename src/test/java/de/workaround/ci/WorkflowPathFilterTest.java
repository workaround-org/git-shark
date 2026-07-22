package de.workaround.ci;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevWalk;
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
 * Path-based trigger filters (issue #2, phase 2): {@code on.push.paths}/{@code paths-ignore} matched
 * against the files changed by the pushed commits (old→new diff). {@code paths} runs when any changed
 * file matches; {@code paths-ignore} runs unless every changed file is ignored.
 */
@QuarkusTest
class WorkflowPathFilterTest
{
	@Inject
	WorkflowIngestService ingest;

	@Inject
	GitRepositoryService repositories;

	@Inject
	ActionRun.Repo runs;

	@Test
	void pathsRunsOnlyWhenAChangedFileMatches()
	{
		String matches = "on:\n  push:\n    paths: ['src/**']\n";
		assertEquals(1, runCount("pf-a", matches, "src/app.js"));

		String noMatch = "on:\n  push:\n    paths: ['docs/**']\n";
		assertEquals(0, runCount("pf-b", noMatch, "src/app.js"));
	}

	@Test
	void pathsIgnoreSkipsOnlyWhenAllChangedFilesAreIgnored()
	{
		String ignore = "on:\n  push:\n    paths-ignore: ['docs/**']\n";
		// changed file is outside the ignore set → still runs
		assertEquals(1, runCount("pf-c", ignore, "src/app.js"));

		String ignoreAll = "on:\n  push:\n    paths-ignore: ['src/**']\n";
		// the only changed file is ignored → skipped
		assertEquals(0, runCount("pf-d", ignoreAll, "src/app.js"));
	}

	private int runCount(String repoName, String onBlock, String changedFile)
	{
		String yaml = onBlock + "jobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo hi\n";
		Repository repo = seedTwoCommitsAndIngest(repoName, yaml, changedFile);
		return runs.findByRepository(repo).size();
	}

	@Transactional
	Repository seedTwoCommitsAndIngest(String repoName, String workflowYaml, String changedFile)
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
			// commit 1: the workflow + a baseline file; commit 2: change only `changedFile`
			GitTestSeeder.seed(bare, Map.of(
				".forgejo/workflows/ci.yml", workflowYaml.getBytes(StandardCharsets.UTF_8),
				"baseline.txt", "base".getBytes(StandardCharsets.UTF_8)));
			GitTestSeeder.seed(bare, Map.of(changedFile, "changed".getBytes(StandardCharsets.UTF_8)));

			try (org.eclipse.jgit.lib.Repository db = new FileRepositoryBuilder().setGitDir(bare.toFile()).build())
			{
				ObjectId newId = db.resolve("refs/heads/main");
				ObjectId oldId;
				try (RevWalk walk = new RevWalk(db))
				{
					oldId = walk.parseCommit(newId).getParent(0).getId();
				}
				ReceiveCommand command = new ReceiveCommand(oldId, newId, "refs/heads/main");
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
