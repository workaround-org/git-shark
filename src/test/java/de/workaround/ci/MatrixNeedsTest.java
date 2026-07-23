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
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code needs} on a matrix job (issue #2, phase 2): a dependent waits for every cell of the needed
 * job, and is cancelled if any cell fails.
 */
@QuarkusTest
class MatrixNeedsTest
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
	void dependentWaitsForEveryMatrixCell()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		UUID runId = seed("mn-a");

		long cellA = claim(reg);           // build (a)
		assertTrue(dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().isPresent(), "second build cell");
		long cellB = otherPendingBuildCell(runId, cellA);

		complete(reg, cellA, Result.RESULT_SUCCESS);
		assertTrue(dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().isEmpty(),
			"deploy still blocked while one build cell is unfinished");

		complete(reg, cellB, Result.RESULT_SUCCESS);
		TaskDispatchService.Fetched deploy = dispatch.fetch(reg.runner().uuid, reg.plaintext());
		assertEquals("deploy", deploy.task().orElseThrow().name);
		assertEquals(ActionRun.Status.SUCCESS, deploy.needs().get("build").result(), "aggregate of all cells");
	}

	@Test
	void oneFailedMatrixCellCancelsDependent()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		UUID runId = seed("mn-b");

		long cellA = claim(reg);
		dispatch.fetch(reg.runner().uuid, reg.plaintext()); // claim the other cell too
		long cellB = otherPendingBuildCell(runId, cellA);

		complete(reg, cellA, Result.RESULT_SUCCESS);
		complete(reg, cellB, Result.RESULT_FAILURE);

		assertEquals(ActionRun.Status.CANCELLED, deployStatus(runId), "a failed cell cancels the dependent");
		assertEquals(ActionRun.Status.FAILURE, runStatus(runId));
	}

	private long claim(RunnerRegistrationService.RegisteredRunner reg)
	{
		return dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().orElseThrow().seq;
	}

	private void complete(RunnerRegistrationService.RegisteredRunner reg, long seq, Result result)
	{
		progress.updateTask(reg.runner().uuid, reg.plaintext(), seq, result, null, java.util.Map.of());
	}

	@Transactional
	long otherPendingBuildCell(UUID runId, long claimedSeq)
	{
		ActionRun run = runs.findById(runId);
		return tasks.findByRun(run).stream()
			.filter(t -> t.jobId.equals("build") && t.seq != claimedSeq)
			.findFirst().orElseThrow().seq;
	}

	private RunnerRegistrationService.RegisteredRunner registerRunner()
	{
		String token = runnerService.createRegistrationToken(persistUser("mn-admin-" + shortId())).plaintext();
		return runnerService.register(token, "mn-runner", List.of(), "v4.0.0", false);
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

		cell(run, "build", "build (a)", "", 20);
		cell(run, "build", "build (b)", "", 15);
		cell(run, "deploy", "deploy", "build", 10);
		return run.id;
	}

	private void cell(ActionRun run, String jobId, String name, String needs, int secondsAgo)
	{
		ActionTask task = new ActionTask();
		task.run = run;
		task.jobId = jobId;
		task.name = name;
		task.needs = needs;
		task.payload = "on: push";
		task.createdAt = Instant.now().minus(secondsAgo, ChronoUnit.SECONDS);
		task.persist();
	}

	@Transactional
	ActionRun.Status runStatus(UUID runId)
	{
		return runs.findById(runId).status;
	}

	@Transactional
	ActionRun.Status deployStatus(UUID runId)
	{
		return tasks.findByRun(runs.findById(runId)).stream()
			.filter(t -> t.jobId.equals("deploy")).findFirst().orElseThrow().status;
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
