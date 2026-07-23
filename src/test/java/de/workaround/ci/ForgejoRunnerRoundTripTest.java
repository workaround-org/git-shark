package de.workaround.ci;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;

import org.jboss.logging.Logger;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionLog;
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
 * End-to-end round-trip against a real {@code gitea/act_runner} binary (issue #2, phase 1 acceptance).
 * A run is queued, an actual runner container registers against this instance, fetches the task over
 * the Connect protocol, executes it, and reports state + logs back — exercising Register / FetchTask /
 * UpdateTask / UpdateLog against the genuine client rather than a hand-built protobuf stub.
 *
 * <p>Runs the job in the runner's own container ({@code :host} label) so no docker-in-docker is
 * needed. Self-skips when no Docker daemon is available.
 */
@QuarkusTest
class ForgejoRunnerRoundTripTest
{
	private static final Logger LOG = Logger.getLogger(ForgejoRunnerRoundTripTest.class);

	private static final org.slf4j.Logger RUNNER_LOG = org.slf4j.LoggerFactory.getLogger("act_runner");

	private static final String RUNNER_IMAGE = "gitea/act_runner:0.2.11";

	private static final int APP_PORT = 8081;

	private static final String WORKFLOW = """
		name: CI
		on: push
		jobs:
		  build:
		    runs-on: ubuntu-latest
		    steps:
		      - run: echo "hello from git-shark ci"
		""";

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

	@Test
	void realRunnerFetchesRunsAndReportsBack() throws Exception
	{
		Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker required for runner round-trip");

		UUID runId = seedRun();
		String registrationToken = mintRegistrationToken();

		Testcontainers.exposeHostPorts(APP_PORT);
		try (GenericContainer<?> runner = new GenericContainer<>(RUNNER_IMAGE)
			.withEnv("GITEA_INSTANCE_URL", "http://host.testcontainers.internal:" + APP_PORT)
			.withEnv("GITEA_RUNNER_REGISTRATION_TOKEN", registrationToken)
			.withEnv("GITEA_RUNNER_NAME", "it-runner")
			.withEnv("GITEA_RUNNER_LABELS", "ubuntu-latest:host")
			.withLogConsumer(new Slf4jLogConsumer(RUNNER_LOG))
			.waitingFor(Wait.forLogMessage(".*(Starting runner daemon|Runner registered).*", 1))
			.withStartupTimeout(Duration.ofMinutes(3)))
		{
			runner.start();

			Status status = pollUntilTerminal(runId, Duration.ofMinutes(2));
			LOG.infof("Final run status: %s (%d log rows)", status.status(), status.logRows());

			assertTrue(status.status().isTerminal(),
				"the real runner should drive the run to a terminal state, was " + status.status());
			assertEquals(ActionRun.Status.SUCCESS, status.status(), "the echo job should succeed");
			assertTrue(status.logRows() > 0, "the runner should have streamed log rows via UpdateLog");
		}
	}

	private Status pollUntilTerminal(UUID runId, Duration timeout) throws InterruptedException
	{
		long deadline = System.nanoTime() + timeout.toNanos();
		Status last = status(runId);
		while (!last.status().isTerminal() && System.nanoTime() < deadline)
		{
			Thread.sleep(2000);
			last = status(runId);
		}
		return last;
	}

	private record Status(ActionRun.Status status, long logRows)
	{
	}

	@Transactional
	Status status(UUID runId)
	{
		ActionRun run = runs.findById(runId);
		List<ActionTask> runTasks = tasks.findByRun(run);
		long rows = runTasks.stream().mapToLong(t -> logs.findByTask(t).size()).sum();
		return new Status(run.status, rows);
	}

	@Transactional
	String mintRegistrationToken()
	{
		String name = "it-admin-" + shortId();
		User admin = new User();
		admin.oidcSub = name;
		admin.username = name;
		admin.persist();
		return runnerService.createRegistrationToken(admin).plaintext();
	}

	@Transactional
	UUID seedRun()
	{
		String name = "it-owner-" + shortId();
		User owner = new User();
		owner.oidcSub = name;
		owner.username = name;
		owner.persist();
		Repository repo = repositories.create(owner, "it-repo", Repository.Visibility.PUBLIC, null);

		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.persist();

		ActionTask task = new ActionTask();
		task.run = run;
		task.name = "build";
		task.jobId = "build";
		task.payload = WORKFLOW;
		task.persist();
		return run.id;
	}

	private static String shortId()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
