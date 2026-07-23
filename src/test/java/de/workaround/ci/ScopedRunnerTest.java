package de.workaround.ci;

import java.time.Instant;
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
 * Repo-scoped runners (issue #2, phase 3): a runner registered for one repository only claims that
 * repository's tasks; an instance-scoped runner claims any.
 */
@QuarkusTest
class ScopedRunnerTest
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
	void repoScopedRunnerSkipsOtherReposTasks()
	{
		User admin = persistUser("sr-admin-" + shortId());
		Repository repoA = newRepo("sr-a");
		Repository repoB = newRepo("sr-b");
		// repoB's task is older, so without scoping it would be picked first
		seedPendingTask(repoB, "b-build", 20);
		UUID aTask = seedPendingTask(repoA, "a-build", 10);

		RunnerRegistrationService.RegisteredRunner scoped = registerScoped(admin, repoA);
		ActionTask claimed = dispatch.fetch(scoped.runner().uuid, scoped.plaintext()).task().orElseThrow();

		assertEquals(aTask, claimed.id, "scoped runner claims its own repo's task, not the older other-repo one");
	}

	@Test
	void repoScopedRunnerGetsNothingWhenOnlyOtherReposHaveWork()
	{
		User admin = persistUser("sr-admin2-" + shortId());
		Repository repoA = newRepo("sr-c");
		Repository repoB = newRepo("sr-d");
		seedPendingTask(repoB, "b-build", 10);

		RunnerRegistrationService.RegisteredRunner scoped = registerScoped(admin, repoA);
		assertTrue(dispatch.fetch(scoped.runner().uuid, scoped.plaintext()).task().isEmpty());
	}

	@Test
	void instanceRunnerClaimsAnyRepo()
	{
		User admin = persistUser("sr-admin3-" + shortId());
		Repository repoB = newRepo("sr-e");
		// dispatch's queue is global; backdate far so this is the oldest pending task and is claimed
		// deterministically regardless of other tests' committed rows
		UUID bTask = seedPendingTask(repoB, "b-build", 86_400);

		String token = runnerService.createRegistrationToken(admin).plaintext();
		RunnerRegistrationService.RegisteredRunner instance = runnerService.register(token, "inst", List.of(),
			"v4.0.0", false);
		assertEquals(bTask, dispatch.fetch(instance.runner().uuid, instance.plaintext()).task().orElseThrow().id,
			"instance runner claims across repos");
	}

	private RunnerRegistrationService.RegisteredRunner registerScoped(User admin, Repository repo)
	{
		String token = runnerService.createRegistrationToken(admin, repo).plaintext();
		return runnerService.register(token, "scoped", List.of(), "v4.0.0", false);
	}

	private Repository newRepo(String name)
	{
		return createRepo(persistUser(name + "-owner-" + shortId()), name);
	}

	@Transactional
	Repository createRepo(User owner, String name)
	{
		return repositories.create(owner, name, Repository.Visibility.PUBLIC, null);
	}

	private UUID seedPendingTask(Repository repository, String jobName, int secondsAgo)
	{
		return seedPendingTask(repository, jobName, secondsAgo, "");
	}

	@Transactional
	UUID seedPendingTask(Repository repository, String jobName, int secondsAgo, String runsOn)
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
		task.jobId = jobName;
		task.runsOn = runsOn;
		task.payload = "on: push";
		task.createdAt = Instant.now().minusSeconds(secondsAgo);
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
