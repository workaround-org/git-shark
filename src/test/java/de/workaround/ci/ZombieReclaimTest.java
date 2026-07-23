package de.workaround.ci;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Zombie reclaim (issue #2, phase 1): a RUNNING task whose deadline has passed — its runner vanished
 * mid-task — is failed cleanly and its run rolled up, while a task still within its deadline is left
 * running.
 */
@QuarkusTest
class ZombieReclaimTest
{
	@Inject
	ZombieReclaimService reclaim;

	@Inject
	RunnerRegistrationService runnerService;

	@Inject
	GitRepositoryService repositories;

	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Inject
	CiRunner.Repo runners;

	@Inject
	TaskProgressService progress;

	@Test
	void overdueTaskIsFailedAndRunnerFreed()
	{
		Instant now = Instant.now();
		CiRunner staleRunner = registerRunner().runner();
		UUID overdue = seedRunningTask("zr-a", staleRunner.uuid, now.minus(5, ChronoUnit.MINUTES));

		CiRunner freshRunner = registerRunner().runner();
		UUID healthy = seedRunningTask("zr-b", freshRunner.uuid, now.plus(30, ChronoUnit.MINUTES));

		int reclaimed = reclaim.reclaim(now);

		assertEquals(1, reclaimed, "only the past-deadline task is reclaimed");

		ActionTask failed = tasks.findById(overdue);
		assertEquals(ActionRun.Status.FAILURE, failed.status);
		assertEquals(ActionRun.Status.FAILURE, failed.run.status);
		assertEquals(CiRunner.Status.OFFLINE, runners.findByUuid(staleRunner.uuid).orElseThrow().status);

		ActionTask running = tasks.findById(healthy);
		assertEquals(ActionRun.Status.RUNNING, running.status);
	}

	@Test
	void lateUpdateCannotResurrectAReclaimedTask()
	{
		Instant now = Instant.now();
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		UUID id = seedRunningTask("zr-res", reg.runner().uuid, now.minus(5, ChronoUnit.MINUTES));

		reclaim.reclaim(now);
		long seq = tasks.findById(id).seq;

		// The presumed-dead runner reconnects and reports success — the task must stay FAILURE.
		progress.updateTask(reg.runner().uuid, reg.plaintext(), seq, Result.RESULT_SUCCESS, null, java.util.Map.of());

		ActionTask task = tasks.findById(id);
		assertEquals(ActionRun.Status.FAILURE, task.status);
		assertEquals(ActionRun.Status.FAILURE, task.run.status);
	}

	private RunnerRegistrationService.RegisteredRunner registerRunner()
	{
		String regToken = runnerService.createRegistrationToken(persistUser("zr-admin-" + shortId())).plaintext();
		return runnerService.register(regToken, "zr-runner", List.of("ubuntu-latest"), "v4.0.0", false);
	}

	@Transactional
	UUID seedRunningTask(String repoName, String runnerUuid, Instant deadline)
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
		task.payload = "on: push";
		task.runner = runner;
		task.status = ActionRun.Status.RUNNING;
		task.startedAt = deadline.minus(1, ChronoUnit.HOURS);
		task.deadline = deadline;
		task.persist();
		return task.id;
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
