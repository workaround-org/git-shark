package de.workaround.ci;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.ci.proto.runner.v1.Result;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.CiRunner;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ephemeral runners (issue #2, phase 3): a runner registered {@code ephemeral} is retired after it
 * finishes its single task, and its credentials no longer work.
 */
@QuarkusTest
class EphemeralRunnerTest
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

	@Inject
	CiRunner.Repo runners;

	@Inject
	ZombieReclaimService reclaim;

	@Inject
	EntityManager em;

	@Test
	void ephemeralRunnerIsRetiredAfterItsTask()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerEphemeral();
		long seq = seedClaimedTask("ep-a", reg.runner().uuid);

		progress.updateTask(reg.runner().uuid, reg.plaintext(), seq, Result.RESULT_SUCCESS, null, java.util.Map.of());

		assertTrue(runners.findByUuid(reg.runner().uuid).isEmpty(), "ephemeral runner removed after its task");

		// its credentials no longer authenticate
		assertThrows(RunnerAuthenticationException.class,
			() -> dispatch.fetch(reg.runner().uuid, reg.plaintext()));
	}

	@Test
	void ephemeralRunnerIsRetiredWhenItsTaskIsReclaimed()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerEphemeral();
		seedOverdueClaimedTask("ep-b", reg.runner().uuid);

		reclaim.reclaim(java.time.Instant.now());

		assertTrue(runners.findByUuid(reg.runner().uuid).isEmpty(),
			"ephemeral runner removed even when its task is reclaimed as a zombie");
	}

	private RunnerRegistrationService.RegisteredRunner registerEphemeral()
	{
		String token = runnerService.createRegistrationToken(persistUser("ep-admin-" + shortId())).plaintext();
		return runnerService.register(token, "ep-runner", List.of(), "v4.0.0", true);
	}

	@Transactional
	long seedClaimedTask(String repoName, String runnerUuid)
	{
		User owner = persistUser(repoName + "-" + shortId());
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);
		CiRunner runner = runners.findByUuid(runnerUuid).orElseThrow();

		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = runs.maxNumber(repo) + 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.status = ActionRun.Status.RUNNING;
		run.persist();

		ActionTask task = new ActionTask();
		task.run = run;
		task.name = "build";
		task.jobId = "build";
		task.payload = "on: push";
		task.runner = runner;
		task.status = ActionRun.Status.RUNNING;
		task.persist();
		em.flush();
		return task.seq;
	}

	@Transactional
	void seedOverdueClaimedTask(String repoName, String runnerUuid)
	{
		User owner = persistUser(repoName + "-" + shortId());
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);
		CiRunner runner = runners.findByUuid(runnerUuid).orElseThrow();

		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = runs.maxNumber(repo) + 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.status = ActionRun.Status.RUNNING;
		run.persist();

		ActionTask task = new ActionTask();
		task.run = run;
		task.name = "build";
		task.jobId = "build";
		task.payload = "on: push";
		task.runner = runner;
		task.status = ActionRun.Status.RUNNING;
		task.deadline = java.time.Instant.now().minusSeconds(300);
		task.persist();
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
