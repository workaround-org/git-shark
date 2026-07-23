package de.workaround.ci;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.Organisation;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Org-scoped runners (issue #2, phase 3): a runner scoped to an organisation runs any of that org's
 * repositories' tasks, but nothing outside it.
 */
@QuarkusTest
class OrgScopedRunnerTest
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
	Organisation.Repo organisations;

	@Test
	void orgScopedRunnerRunsItsOrgReposButNotOthers()
	{
		User admin = persistUser("org-admin-" + shortId());
		Organisation org = persistOrg("org-" + shortId());
		UUID orgTask = seedOrgRepoTask(org, "org-repo", 10);
		seedUserRepoTask("org-outsider", 20); // older, different owner — must be skipped

		RunnerRegistrationService.RegisteredRunner scoped = registerForOrg(admin, org);
		ActionTask claimed = dispatch.fetch(scoped.runner().uuid, scoped.plaintext()).task().orElseThrow();

		assertEquals(orgTask, claimed.id, "org runner claims its org's repo task, not the older outside one");
	}

	@Test
	void orgScopedRunnerIdlesWhenOnlyOtherOwnersHaveWork()
	{
		User admin = persistUser("org-admin2-" + shortId());
		Organisation org = persistOrg("org2-" + shortId());
		seedUserRepoTask("org-outsider2", 10);

		RunnerRegistrationService.RegisteredRunner scoped = registerForOrg(admin, org);
		assertTrue(dispatch.fetch(scoped.runner().uuid, scoped.plaintext()).task().isEmpty());
	}

	private RunnerRegistrationService.RegisteredRunner registerForOrg(User admin, Organisation org)
	{
		String token = runnerService.createRegistrationToken(admin, org).plaintext();
		return runnerService.register(token, "org-runner", List.of(), "v4.0.0", false);
	}

	@Transactional
	UUID seedOrgRepoTask(Organisation organisation, String repoName, int secondsAgo)
	{
		Organisation org = organisations.findById(organisation.id);
		Repository repo = new Repository();
		repo.name = repoName;
		repo.ownerOrg = org;
		repo.visibility = Repository.Visibility.PUBLIC;
		repo.persist();
		return seedTask(repo, secondsAgo);
	}

	@Transactional
	UUID seedUserRepoTask(String name, int secondsAgo)
	{
		User owner = persistUser(name + "-" + shortId());
		Repository repo = repositories.create(owner, name, Repository.Visibility.PUBLIC, null);
		return seedTask(repositories.find(repo.ownerHandle(), repo.name).orElseThrow(), secondsAgo);
	}

	private UUID seedTask(Repository repo, int secondsAgo)
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

		ActionTask task = new ActionTask();
		task.run = run;
		task.name = "build";
		task.jobId = "build";
		task.payload = "on: push";
		task.createdAt = Instant.now().minusSeconds(secondsAgo);
		task.persist();
		return task.id;
	}

	@Transactional
	Organisation persistOrg(String name)
	{
		Organisation org = new Organisation();
		org.name = name;
		org.displayName = name;
		org.persist();
		return org;
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
