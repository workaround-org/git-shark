package de.workaround.ci;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Job dependency ordering (issue #2, phase 2, {@code needs}): a task is not dispatched until every
 * job it needs has succeeded; when a needed job fails, dependents are cancelled so the run finishes;
 * a dispatched task carries its needs' results.
 */
@QuarkusTest
class NeedsOrderingTest
{
	@Inject
	RunnerRegistrationService runnerService;

	@Inject
	TaskDispatchService dispatch;

	@Inject
	TaskProgressService progress;

	@Inject
	GitRepositoryService repositories;

	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Test
	void dependentTaskWaitsForItsNeedThenReceivesItsResult()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		UUID runId = seed("no-a");

		// only "build" is ready; "deploy" needs "build"
		ActionTask first = dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().orElseThrow();
		assertEquals("build", first.name);
		long buildSeq = first.seq;

		assertTrue(dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().isEmpty(),
			"deploy stays blocked while build is running");

		progress.updateTask(reg.runner().uuid, reg.plaintext(), buildSeq,
			de.workaround.ci.proto.runner.v1.Result.RESULT_SUCCESS, null);

		TaskDispatchService.Fetched second = dispatch.fetch(reg.runner().uuid, reg.plaintext());
		assertEquals("deploy", second.task().orElseThrow().name);
		assertEquals(ActionRun.Status.SUCCESS, second.needs().get("build"), "needs result delivered");

		assertTrue(runIsRunning(runId));
	}

	@Test
	void failedNeedCancelsDependentAndEndsRun()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		UUID runId = seed("no-b");

		ActionTask build = dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().orElseThrow();
		progress.updateTask(reg.runner().uuid, reg.plaintext(), build.seq,
			de.workaround.ci.proto.runner.v1.Result.RESULT_FAILURE, null);

		assertEquals(ActionRun.Status.CANCELLED, deployStatus(runId), "dependent of a failed job is cancelled");
		assertEquals(ActionRun.Status.FAILURE, runStatus(runId), "run finishes rather than hanging");
		assertTrue(dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().isEmpty());
	}

	private RunnerRegistrationService.RegisteredRunner registerRunner()
	{
		String token = runnerService.createRegistrationToken(persistUser("no-admin-" + shortId())).plaintext();
		return runnerService.register(token, "no-runner", List.of(), "v4.0.0", false);
	}

	@Transactional
	UUID seed(String repoName)
	{
		User owner = persistUser(repoName + "-" + shortId());
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);

		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = runs.maxNumber(repo) + 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.persist();

		newTask(run, "build", "", 10);
		newTask(run, "deploy", "build", 5);
		return run.id;
	}

	private void newTask(ActionRun run, String name, String needs, int secondsAgo)
	{
		ActionTask task = new ActionTask();
		task.run = run;
		task.name = name;
		task.needs = needs;
		task.payload = "on: push";
		task.createdAt = Instant.now().minusSeconds(secondsAgo);
		task.persist();
	}

	@Transactional
	boolean runIsRunning(UUID runId)
	{
		return runs.findById(runId).status == ActionRun.Status.RUNNING;
	}

	@Transactional
	ActionRun.Status runStatus(UUID runId)
	{
		return runs.findById(runId).status;
	}

	@Transactional
	ActionRun.Status deployStatus(UUID runId)
	{
		ActionRun run = runs.findById(runId);
		return tasks.findByRun(run).stream().filter(t -> t.name.equals("deploy")).findFirst().orElseThrow().status;
	}

	@Transactional
	User persistUser(String name)
	{
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.persist();
		return user;
	}

	private static String shortId()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}
}
