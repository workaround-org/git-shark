package de.workaround.ci;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.google.protobuf.InvalidProtocolBufferException;

import de.workaround.ci.proto.runner.v1.FetchTaskRequest;
import de.workaround.ci.proto.runner.v1.FetchTaskResponse;
import de.workaround.git.GitRepositoryService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives FetchTask over the Connect wire (issue #2, phase 1): a registered runner claims the oldest
 * PENDING task, which flips to RUNNING and is delivered with its surrogate int64 id and payload;
 * an empty queue yields no task; bad credentials are rejected.
 */
@QuarkusTest
class FetchTaskTest
{
	private static final String PROTO = "application/proto";

	@Inject
	RunnerRegistrationService runnerService;

	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo users;

	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Test
	void fetchClaimsOldestPendingTask() throws InvalidProtocolBufferException
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		Repository repo = newRepo("ft-a");
		UUID taskId = seedPendingTask(repo, "build", "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest");

		byte[] bytes = given()
			.contentType(PROTO)
			.header("x-runner-uuid", reg.runner().uuid)
			.header("x-runner-token", reg.plaintext())
			.body(FetchTaskRequest.newBuilder().setTasksVersion(0).build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/FetchTask")
			.then().statusCode(200)
			.extract().asByteArray();

		FetchTaskResponse response = FetchTaskResponse.parseFrom(bytes);
		assertTrue(response.hasTask(), "a pending task must be handed out");
		assertTrue(response.getTask().getId() > 0, "task carries its surrogate int64 id");
		assertEquals("on: push\njobs:\n  build:\n    runs-on: ubuntu-latest",
			response.getTask().getWorkflowPayload().toStringUtf8());

		ActionTask claimed = tasks.findById(taskId);
		assertEquals(ActionRun.Status.RUNNING, claimed.status);
		assertEquals(response.getTask().getId(), claimed.seq);
		assertEquals(reg.runner().uuid, claimed.runner.uuid);
		assertEquals(ActionRun.Status.RUNNING, claimed.run.status);
		assertEquals(CiRunner.Status.ACTIVE, claimed.runner.status);
	}

	@Test
	void fetchWithEmptyQueueReturnsNoTask() throws InvalidProtocolBufferException
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();

		byte[] bytes = given()
			.contentType(PROTO)
			.header("x-runner-uuid", reg.runner().uuid)
			.header("x-runner-token", reg.plaintext())
			.body(FetchTaskRequest.newBuilder().setTasksVersion(0).build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/FetchTask")
			.then().statusCode(200)
			.extract().asByteArray();

		FetchTaskResponse response = FetchTaskResponse.parseFrom(bytes);
		assertFalse(response.hasTask(), "no pending task means an empty response");
	}

	@Test
	void concurrentFetchesClaimTheSameTaskAtMostOnce() throws Exception
	{
		RunnerRegistrationService.RegisteredRunner a = registerRunner();
		RunnerRegistrationService.RegisteredRunner b = registerRunner();
		Repository repo = newRepo("ft-race");
		seedPendingTask(repo, "build", "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest");

		CyclicBarrier start = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try
		{
			Future<Boolean> f1 = pool.submit(fetchGotTask(a, start));
			Future<Boolean> f2 = pool.submit(fetchGotTask(b, start));
			int handedOut = (f1.get() ? 1 : 0) + (f2.get() ? 1 : 0);
			assertEquals(1, handedOut, "exactly one runner may claim the single pending task");
		}
		finally
		{
			pool.shutdownNow();
		}
	}

	private Callable<Boolean> fetchGotTask(RunnerRegistrationService.RegisteredRunner runner, CyclicBarrier start)
	{
		return () ->
		{
			start.await();
			byte[] bytes = given()
				.contentType(PROTO)
				.header("x-runner-uuid", runner.runner().uuid)
				.header("x-runner-token", runner.plaintext())
				.body(FetchTaskRequest.newBuilder().setTasksVersion(0).build().toByteArray())
				.when().post("/api/actions/runner.v1.RunnerService/FetchTask")
				.then().statusCode(200)
				.extract().asByteArray();
			return FetchTaskResponse.parseFrom(bytes).hasTask();
		};
	}

	@Test
	void fetchWithBadCredentialsIsUnauthenticated()
	{
		given()
			.contentType(PROTO)
			.header("x-runner-uuid", "nope")
			.header("x-runner-token", "gsrt_bogus")
			.body(FetchTaskRequest.newBuilder().setTasksVersion(0).build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/FetchTask")
			.then().statusCode(401);
	}

	private RunnerRegistrationService.RegisteredRunner registerRunner()
	{
		String regToken = runnerService.createRegistrationToken(persistUser("ft-admin-" + shortId())).plaintext();
		return runnerService.register(regToken, "ft-runner", List.of("ubuntu-latest"), "v4.0.0", false);
	}

	private Repository newRepo(String name)
	{
		User owner = persistUser(name + "-" + shortId());
		return repositories.create(owner, name, Repository.Visibility.PUBLIC, null);
	}

	@Transactional
	UUID seedPendingTask(Repository repository, String jobName, String payload)
	{
		Repository repo = repositories.find(repository.ownerHandle(), repository.name).orElseThrow();
		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = runs.maxNumber(repo) + 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.persist();

		ActionTask task = new ActionTask();
		task.run = run;
		task.name = jobName;
		task.payload = payload;
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
