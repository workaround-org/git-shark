package de.workaround.ci;

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
 * Label-based task-to-runner matching (issue #2, phase 2): a runner only claims tasks whose
 * {@code runs-on} labels it advertises; a task with no label constraint runs on any runner.
 */
@QuarkusTest
class LabelMatchingTest
{
	@Inject
	RunnerRegistrationService runnerService;

	@Inject
	TaskDispatchService dispatch;

	@Inject
	GitRepositoryService repositories;

	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Test
	void runnerClaimsCompatibleTaskAndSkipsIncompatibleOlderOne()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner("ubuntu-latest");
		// windows task is older (would be picked first without label matching); ubuntu task is newer
		Ids ids = seedTwoTasks("lm-a", "windows", "ubuntu-latest");

		ActionTask claimed = dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().orElseThrow();
		assertEquals("ubuntu-latest", claimed.runsOn);

		assertEquals(ActionRun.Status.PENDING, tasks.findById(ids.first()).status, "incompatible task stays queued");
		assertEquals(ActionRun.Status.RUNNING, tasks.findById(ids.second()).status);
	}

	@Test
	void runnerGetsNothingWhenNoTaskMatchesItsLabels()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner("arm64");
		seedTwoTasks("lm-b", "ubuntu-latest", "windows");

		assertTrue(dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().isEmpty());
	}

	@Test
	void unconstrainedTaskRunsOnAnyRunner()
	{
		RunnerRegistrationService.RegisteredRunner reg = registerRunner("whatever");
		seedTwoTasks("lm-c", "", "windows"); // first task has no runs-on constraint

		ActionTask claimed = dispatch.fetch(reg.runner().uuid, reg.plaintext()).task().orElseThrow();
		assertEquals("", claimed.runsOn);
	}

	private record Ids(UUID first, UUID second)
	{
	}

	private RunnerRegistrationService.RegisteredRunner registerRunner(String label)
	{
		String token = runnerService.createRegistrationToken(persistUser("lm-admin-" + shortId())).plaintext();
		return runnerService.register(token, "lm-runner", List.of(label), "v4.0.0", false);
	}

	@Transactional
	Ids seedTwoTasks(String repoName, String firstRunsOn, String secondRunsOn)
	{
		User owner = persistUser(repoName + "-" + shortId());
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);
		ActionRun run = newRun(repo);
		UUID first = newTask(run, "first", firstRunsOn, 10);
		UUID second = newTask(run, "second", secondRunsOn, 5);
		return new Ids(first, second);
	}

	private ActionRun newRun(Repository repo)
	{
		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = runs.maxNumber(repo) + 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.persist();
		return run;
	}

	private UUID newTask(ActionRun run, String name, String runsOn, int secondsAgo)
	{
		ActionTask task = new ActionTask();
		task.run = run;
		task.name = name;
		task.runsOn = runsOn;
		task.payload = "on: push";
		task.createdAt = java.time.Instant.now().minusSeconds(secondsAgo);
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
