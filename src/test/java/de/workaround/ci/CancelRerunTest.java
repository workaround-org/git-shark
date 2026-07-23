package de.workaround.ci;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.protobuf.InvalidProtocolBufferException;

import de.workaround.ci.proto.runner.v1.Result;
import de.workaround.ci.proto.runner.v1.TaskState;
import de.workaround.ci.proto.runner.v1.UpdateTaskRequest;
import de.workaround.ci.proto.runner.v1.UpdateTaskResponse;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionLog;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.CiRunner;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run cancellation and re-run (issue #2, phase 2). Cancelling settles a run and its unfinished tasks,
 * and a still-running task's runner learns of it via the UpdateTask response. Re-run resets a finished
 * run's tasks back to PENDING.
 */
@QuarkusTest
class CancelRerunTest
{
	private static final String PROTO = "application/proto";

	@Inject
	ActionRunService runService;

	@Inject
	RunnerRegistrationService runnerService;

	@Inject
	GitRepositoryService repositories;

	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Inject
	ActionLog.Repo logs;

	@Inject
	CiRunner.Repo runners;

	@Inject
	EntityManager em;

	@Test
	@TestTransaction
	void cancelSettlesRunAndUnfinishedTasks()
	{
		Repository repo = repo("cr-a");
		ActionRun run = newRun(repo, ActionRun.Status.RUNNING);
		UUID running = newTask(run, "build", ActionRun.Status.RUNNING);
		UUID pending = newTask(run, "deploy", ActionRun.Status.PENDING);

		runService.cancel(run);

		assertEquals(ActionRun.Status.CANCELLED, runs.findById(run.id).status);
		assertEquals(ActionRun.Status.CANCELLED, tasks.findById(running).status);
		assertEquals(ActionRun.Status.CANCELLED, tasks.findById(pending).status);
	}

	@Test
	@TestTransaction
	void rerunResetsFinishedRunToPending()
	{
		Repository repo = repo("cr-b");
		ActionRun run = newRun(repo, ActionRun.Status.SUCCESS);
		run.finishedAt = Instant.now();
		UUID taskId = newTask(run, "build", ActionRun.Status.SUCCESS);
		ActionTask task = tasks.findById(taskId);
		task.finishedAt = Instant.now();
		task.logLength = 2;
		task.outputs = "{\"image\":\"x\"}";
		ActionLog log = new ActionLog();
		log.task = task;
		log.lineIndex = 0;
		log.content = "hi";
		log.persist();

		runService.rerun(run);

		ActionRun reloaded = runs.findById(run.id);
		ActionTask reloadedTask = tasks.findById(taskId);
		assertEquals(ActionRun.Status.PENDING, reloaded.status);
		assertNull(reloaded.finishedAt);
		assertEquals(ActionRun.Status.PENDING, reloadedTask.status);
		assertNull(reloadedTask.runner);
		assertNull(reloadedTask.finishedAt);
		assertEquals(0, reloadedTask.logLength);
		assertEquals("{}", reloadedTask.outputs);
		assertTrue(logs.findByTask(reloadedTask).isEmpty(), "old logs cleared");
	}

	@Test
	@TestTransaction
	void cancelIsNoOpOnAlreadyFinishedRun()
	{
		Repository repo = repo("cr-d");
		ActionRun run = newRun(repo, ActionRun.Status.SUCCESS);
		Instant finished = Instant.now().minusSeconds(60);
		run.finishedAt = finished;

		runService.cancel(run);

		ActionRun reloaded = runs.findById(run.id);
		assertEquals(ActionRun.Status.SUCCESS, reloaded.status, "a finished run is not rewritten to CANCELLED");
		assertEquals(finished, reloaded.finishedAt);
	}

	@Test
	@TestTransaction
	void rerunIsNoOpWhileRunStillRunning()
	{
		Repository repo = repo("cr-e");
		ActionRun run = newRun(repo, ActionRun.Status.RUNNING);
		UUID taskId = newTask(run, "build", ActionRun.Status.RUNNING);

		runService.rerun(run);

		assertEquals(ActionRun.Status.RUNNING, runs.findById(run.id).status, "a running run is not reset");
		assertEquals(ActionRun.Status.RUNNING, tasks.findById(taskId).status);
	}

	@Test
	void cancelledTaskTellsRunnerToStopOnUpdate() throws InvalidProtocolBufferException
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		long seq = seedClaimedThenCancelled("cr-c", reg.runner().uuid);

		byte[] bytes = given()
			.contentType(PROTO)
			.header("x-runner-uuid", reg.runner().uuid)
			.header("x-runner-token", reg.plaintext())
			.body(UpdateTaskRequest.newBuilder()
				.setState(TaskState.newBuilder().setId(seq).setResult(Result.RESULT_UNSPECIFIED))
				.build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/UpdateTask")
			.then().statusCode(200)
			.extract().asByteArray();

		UpdateTaskResponse response = UpdateTaskResponse.parseFrom(bytes);
		assertEquals(Result.RESULT_CANCELLED, response.getState().getResult(),
			"the runner is told the task was cancelled");
	}

	@Transactional
	long seedClaimedThenCancelled(String repoName, String runnerUuid)
	{
		Repository repo = repo(repoName);
		CiRunner runner = runners.findByUuid(runnerUuid).orElseThrow();
		ActionRun run = newRun(repo, ActionRun.Status.RUNNING);
		ActionTask task = new ActionTask();
		task.run = run;
		task.name = "build";
		task.payload = "on: push";
		task.runner = runner;
		task.status = ActionRun.Status.RUNNING;
		task.persist();
		em.flush();
		runService.cancel(run);
		return task.seq;
	}

	private RunnerRegistrationService.RegisteredRunner registerRunner()
	{
		String token = runnerService.createRegistrationToken(persistUser("cr-admin-" + shortId())).plaintext();
		return runnerService.register(token, "cr-runner", List.of(), "v4.0.0", false);
	}

	private Repository repo(String name)
	{
		User owner = persistUser(name + "-" + shortId());
		return repositories.create(owner, name, Repository.Visibility.PUBLIC, null);
	}

	private ActionRun newRun(Repository repo, ActionRun.Status status)
	{
		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = runs.maxNumber(repo) + 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.status = status;
		run.persist();
		return run;
	}

	private UUID newTask(ActionRun run, String name, ActionRun.Status status)
	{
		ActionTask task = new ActionTask();
		task.run = run;
		task.name = name;
		task.payload = "on: push";
		task.status = status;
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
