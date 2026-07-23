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
 * Superseded-run cancellation (issue #2, phase 2): a new push to a branch cancels the branch's
 * still-running earlier runs, but leaves runs on other branches alone.
 */
@QuarkusTest
class SupersededRunsTest
{
	@Inject
	WorkflowIngestService ingest;

	@Inject
	GitRepositoryService repositories;

	@Inject
	ActionRun.Repo runs;

	private static final String WORKFLOW = "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n"
		+ "    steps:\n      - run: echo hi\n";

	@Test
	void newPushCancelsEarlierRunningRunOnSameBranch()
	{
		Repository repo = seedRepo("sup-a");
		UUID oldRun = seedActiveRun(repo, "refs/heads/main", ActionRun.Status.RUNNING);
		UUID otherBranch = seedActiveRun(repo, "refs/heads/dev", ActionRun.Status.RUNNING);

		pushWorkflowToMain(repo);

		assertEquals(ActionRun.Status.CANCELLED, statusOf(oldRun), "earlier run on the pushed branch is superseded");
		assertEquals(ActionRun.Status.RUNNING, statusOf(otherBranch), "a run on another branch is untouched");
		assertEquals(1, activeCount(repo, "refs/heads/main"), "exactly the new run stays active on main");
	}

	@Transactional
	long activeCount(Repository repo, String ref)
	{
		return runs.findActiveByRepositoryAndRef(repositories.find(repo.ownerHandle(), repo.name).orElseThrow(), ref)
			.size();
	}

	private void pushWorkflowToMain(Repository repo)
	{
		try
		{
			Path bare = repositories.repositoryPath(repo);
			GitTestSeeder.seed(bare, Map.of(".forgejo/workflows/ci.yml", WORKFLOW.getBytes(StandardCharsets.UTF_8)));
			try (org.eclipse.jgit.lib.Repository db = new FileRepositoryBuilder().setGitDir(bare.toFile()).build())
			{
				ObjectId head = db.resolve("refs/heads/main");
				ReceiveCommand command = new ReceiveCommand(ObjectId.zeroId(), head, "refs/heads/main");
				command.setResult(ReceiveCommand.Result.OK);
				ingest.onPush(repo.ownerHandle(), repo.name, null, db, List.of(command));
			}
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	@Transactional
	Repository seedRepo(String name)
	{
		User owner = new User();
		owner.oidcSub = name + "-" + shortId();
		owner.username = owner.oidcSub;
		owner.persist();
		return repositories.create(owner, name, Repository.Visibility.PUBLIC, null);
	}

	@Transactional
	UUID seedActiveRun(Repository repo, String ref, ActionRun.Status status)
	{
		ActionRun run = new ActionRun();
		run.repository = repositories.find(repo.ownerHandle(), repo.name).orElseThrow();
		run.number = runs.maxNumber(run.repository) + 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = ref;
		run.commitSha = "0000000000000000000000000000000000000000";
		run.status = status;
		run.persist();
		return run.id;
	}

	@Transactional
	ActionRun.Status statusOf(UUID runId)
	{
		return runs.findById(runId).status;
	}

	private static String shortId()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}
}
