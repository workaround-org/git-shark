package de.workaround.ci;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.protobuf.InvalidProtocolBufferException;

import de.workaround.ci.proto.runner.v1.LogRow;
import de.workaround.ci.proto.runner.v1.Result;
import de.workaround.ci.proto.runner.v1.TaskState;
import de.workaround.ci.proto.runner.v1.UpdateLogRequest;
import de.workaround.ci.proto.runner.v1.UpdateLogResponse;
import de.workaround.ci.proto.runner.v1.UpdateTaskRequest;
import de.workaround.ci.proto.runner.v1.UpdateTaskResponse;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionLog;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.CiRunner;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Drives UpdateTask and UpdateLog over the Connect wire (issue #2, phase 1): a runner reports its
 * claimed task's result (task+run go terminal, runner returns IDLE) and streams log rows with
 * resume-safe append semantics (duplicate rows are ignored, ack_index is the durable row count).
 */
@QuarkusTest
class TaskProgressTest
{
	private static final String PROTO = "application/proto";

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
	jakarta.persistence.EntityManager em;

	@Test
	void updateTaskWithSuccessMarksTaskAndRunDone() throws InvalidProtocolBufferException
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		long seq = seedClaimedTask("tp-a", reg.runner().uuid);

		byte[] bytes = given()
			.contentType(PROTO)
			.header("x-runner-uuid", reg.runner().uuid)
			.header("x-runner-token", reg.plaintext())
			.body(UpdateTaskRequest.newBuilder()
				.setState(TaskState.newBuilder().setId(seq).setResult(Result.RESULT_SUCCESS))
				.build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/UpdateTask")
			.then().statusCode(200)
			.extract().asByteArray();

		UpdateTaskResponse response = UpdateTaskResponse.parseFrom(bytes);
		assertEquals(seq, response.getState().getId());

		ActionTask task = tasks.findBySeq(seq).orElseThrow();
		assertEquals(ActionRun.Status.SUCCESS, task.status);
		assertEquals(ActionRun.Status.SUCCESS, task.run.status);
		assertEquals(CiRunner.Status.IDLE, task.runner.status);
	}

	@Test
	void updateLogAppendsRowsAndIsResumeSafe() throws InvalidProtocolBufferException
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		long seq = seedClaimedTask("tp-b", reg.runner().uuid);

		UpdateLogResponse first = UpdateLogResponse.parseFrom(sendLog(reg, seq, 0, List.of("line 0", "line 1")));
		assertEquals(2, first.getAckIndex(), "two contiguous rows accepted from index 0");

		// Runner resends line 1 (already stored) plus a new line 2 — the dup must be ignored.
		UpdateLogResponse second = UpdateLogResponse.parseFrom(sendLog(reg, seq, 1, List.of("line 1", "line 2")));
		assertEquals(3, second.getAckIndex(), "ack is the durable row count, dup ignored");

		ActionTask task = tasks.findBySeq(seq).orElseThrow();
		assertEquals(3, task.logLength);
		List<ActionLog> stored = logs.findByTask(task);
		assertEquals(3, stored.size());
		assertEquals("line 0", stored.get(0).content);
		assertEquals("line 2", stored.get(2).content);
	}

	@Test
	void updateTaskByAnotherRunnerIsRejected()
	{
		RunnerRegistrationService.RegisteredRunner owner = registerRunner();
		RunnerRegistrationService.RegisteredRunner other = registerRunner();
		long seq = seedClaimedTask("tp-c", owner.runner().uuid);

		given()
			.contentType(PROTO)
			.header("x-runner-uuid", other.runner().uuid)
			.header("x-runner-token", other.plaintext())
			.body(UpdateTaskRequest.newBuilder()
				.setState(TaskState.newBuilder().setId(seq).setResult(Result.RESULT_SUCCESS))
				.build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/UpdateTask")
			.then().statusCode(401);
	}

	@Test
	void updateLogWithBadCredentialsIsUnauthenticated()
	{
		given()
			.contentType(PROTO)
			.header("x-runner-uuid", "nope")
			.header("x-runner-token", "gsrt_bogus")
			.body(UpdateLogRequest.newBuilder().setTaskId(1).build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/UpdateLog")
			.then().statusCode(401);
	}

	private byte[] sendLog(RunnerRegistrationService.RegisteredRunner reg, long seq, long index, List<String> lines)
	{
		UpdateLogRequest.Builder request = UpdateLogRequest.newBuilder().setTaskId(seq).setIndex(index);
		for (String line : lines)
		{
			request.addRows(LogRow.newBuilder().setContent(line));
		}
		return given()
			.contentType(PROTO)
			.header("x-runner-uuid", reg.runner().uuid)
			.header("x-runner-token", reg.plaintext())
			.body(request.build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/UpdateLog")
			.then().statusCode(200)
			.extract().asByteArray();
	}

	private RunnerRegistrationService.RegisteredRunner registerRunner()
	{
		String regToken = runnerService.createRegistrationToken(persistUser("tp-admin-" + shortId())).plaintext();
		return runnerService.register(regToken, "tp-runner", List.of("ubuntu-latest"), "v4.0.0", false);
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
		task.payload = "on: push";
		task.runner = runner;
		task.status = ActionRun.Status.RUNNING;
		task.persist();
		em.flush(); // populate the DB-generated seq before reading it
		return task.seq;
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
