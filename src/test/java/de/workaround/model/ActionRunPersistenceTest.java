package de.workaround.model;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit 1 of the CI/CD run loop (issue #2, phase 1): the persistence foundation
 * (action_run / action_task / action_log) that FetchTask/UpdateTask/UpdateLog build on.
 */
@QuarkusTest
class ActionRunPersistenceTest
{
	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Inject
	ActionLog.Repo logs;

	@Test
	@TestTransaction
	void persistsRunWithTaskAndLogs()
	{
		Repository repo = newRepo("alpha");

		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "deadbeefdeadbeefdeadbeefdeadbeefdeadbeef";
		run.persist();

		ActionTask task = new ActionTask();
		task.run = run;
		task.name = "build";
		task.payload = "jobs:\n  build:\n    runs-on: ubuntu-latest";
		task.persist();

		ActionLog line0 = new ActionLog();
		line0.task = task;
		line0.lineIndex = 0;
		line0.content = "Running build";
		line0.persist();

		assertNotNull(run.id);
		assertNotNull(task.id);
		assertNotNull(line0.id);
		assertEquals(ActionRun.Status.PENDING, run.status);
		assertEquals(ActionRun.Status.PENDING, task.status);

		List<ActionTask> ofRun = tasks.findByRun(run);
		assertEquals(1, ofRun.size());
		assertEquals("build", ofRun.get(0).name);
		assertEquals(run.id, ofRun.get(0).run.id);

		List<ActionLog> ofTask = logs.findByTask(task);
		assertEquals(1, ofTask.size());
		assertEquals("Running build", ofTask.get(0).content);
	}

	@Test
	@TestTransaction
	void runNumbersAreScopedPerRepository()
	{
		Repository repoA = newRepo("beta");
		Repository repoB = newRepo("gamma");

		assertEquals(0, runs.maxNumber(repoA));

		newRun(repoA, 1);
		newRun(repoA, 2);
		newRun(repoB, 1);

		assertEquals(2, runs.maxNumber(repoA));
		assertEquals(1, runs.maxNumber(repoB));

		ActionRun found = runs.findByRepositoryAndNumber(repoA, 2).orElseThrow();
		assertEquals(2, found.number);
		assertEquals(repoA.id, found.repository.id);
	}

	@Test
	@TestTransaction
	void findsOldestPendingTaskForDispatch()
	{
		Repository repo = newRepo("delta");
		ActionRun run = newRun(repo, 1);

		ActionTask done = new ActionTask();
		done.run = run;
		done.name = "old";
		done.status = ActionRun.Status.SUCCESS;
		done.persist();

		ActionTask pending = new ActionTask();
		pending.run = run;
		pending.name = "new";
		pending.persist();

		ActionTask next = tasks.findOldestPending().orElseThrow();
		assertEquals("new", next.name);
		assertTrue(next.status == ActionRun.Status.PENDING);
	}

	private ActionRun newRun(Repository repo, int number)
	{
		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = number;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.persist();
		return run;
	}

	private static Repository newRepo(String name)
	{
		User owner = new User();
		owner.oidcSub = "sub-" + UUID.randomUUID();
		owner.username = name + "-" + UUID.randomUUID();
		owner.persist();

		Repository repo = new Repository();
		repo.name = name;
		repo.ownerUser = owner;
		repo.visibility = Repository.Visibility.PUBLIC;
		repo.persist();
		return repo;
	}

}
