package de.workaround.ci;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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

/**
 * Job outputs flowing through {@code needs} (issue #2, phase 2): a job reports outputs via UpdateTask;
 * a dependent job then receives them in its needs context when it is dispatched.
 */
@QuarkusTest
class NeedsOutputsTest
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
	void dependentReceivesUpstreamJobOutputs()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		seed("nof-a");

		ActionTask build = dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().orElseThrow();
		assertEquals("build", build.name);

		progress.updateTask(reg.runner().uuid, reg.plaintext(), build.seq, Result.RESULT_SUCCESS, null,
			Map.of("image", "app:1.2.3", "digest", "sha256:abc"));

		TaskDispatchService.Fetched deploy = dispatch.fetch(reg.runner().uuid, reg.plaintext());
		assertEquals("deploy", deploy.task().orElseThrow().name);
		TaskDispatchService.NeedInfo buildNeed = deploy.needs().get("build");
		assertEquals(ActionRun.Status.SUCCESS, buildNeed.result());
		assertEquals("app:1.2.3", buildNeed.outputs().get("image"));
		assertEquals("sha256:abc", buildNeed.outputs().get("digest"));
	}

	@Test
	void outputsAccumulateAcrossUpdates()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner();
		seed("nof-b");

		ActionTask build = dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().orElseThrow();
		// runner sends outputs incrementally, only the unsent ones each time
		progress.updateTask(reg.runner().uuid, reg.plaintext(), build.seq, Result.RESULT_UNSPECIFIED, null,
			Map.of("image", "app:1.2.3"));
		progress.updateTask(reg.runner().uuid, reg.plaintext(), build.seq, Result.RESULT_SUCCESS, null,
			Map.of("digest", "sha256:abc"));

		TaskDispatchService.NeedInfo buildNeed =
			dispatch.fetch(reg.runner().uuid, reg.plaintext()).needs().get("build");
		assertEquals("app:1.2.3", buildNeed.outputs().get("image"), "earlier output retained");
		assertEquals("sha256:abc", buildNeed.outputs().get("digest"), "later output merged");
	}

	private RunnerRegistrationService.RegisteredRunner registerRunner()
	{
		String token = runnerService.createRegistrationToken(persistUser("nof-admin-" + shortId())).plaintext();
		return runnerService.register(token, "nof-runner", List.of(), "v4.0.0", false);
	}

	@Transactional
	void seed(String repoName)
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
