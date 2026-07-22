package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionLog;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The per-repository Actions UI (issue #2, phase 1): a run list and a run detail page showing the
 * run's tasks and their log rows, server-rendered like the issues pages.
 */
@QuarkusTest
class ActionUiTest
{
	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo users;

	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Inject
	ActionLog.Repo logs;

	@Test
	void runListShowsRunsAndActionsTab()
	{
		Seed seed = seedRun("act-a");

		given().when().get("/repos/" + seed.owner + "/act-a/actions")
			.then().statusCode(200)
			.body(containsString("class=\"repo-nav\""))
			.body(containsString("active"))
			.body(containsString("CI"))
			.body(containsString("Success"))
			.body(containsString("#1"));
	}

	@Test
	void runDetailShowsTasksAndLogs()
	{
		Seed seed = seedRun("act-b");

		given().when().get("/repos/" + seed.owner + "/act-b/actions/1")
			.then().statusCode(200)
			.body(containsString("build"))
			.body(containsString("line 0"))
			.body(containsString("line 1"));
	}

	@Test
	void unknownRunNumberIs404()
	{
		Seed seed = seedRun("act-c");

		given().when().get("/repos/" + seed.owner + "/act-c/actions/999")
			.then().statusCode(404);
	}

	@Test
	void privateRepoActionsHiddenFromStrangers()
	{
		String owner = seedPrivateRepo("act-priv");

		given().when().get("/repos/" + owner + "/act-priv/actions")
			.then().statusCode(404);
		given().when().get("/repos/" + owner + "/act-priv/actions/1")
			.then().statusCode(404);
	}

	@Transactional
	String seedPrivateRepo(String repoName)
	{
		String username = repoName + "-" + UUID.randomUUID().toString().substring(0, 8);
		User owner = new User();
		owner.oidcSub = username;
		owner.username = username;
		owner.persist();
		repositories.create(owner, repoName, Repository.Visibility.PRIVATE, null);
		return username;
	}

	private record Seed(String owner)
	{
	}

	@Transactional
	Seed seedRun(String repoName)
	{
		String username = repoName + "-" + UUID.randomUUID().toString().substring(0, 8);
		User owner = new User();
		owner.oidcSub = username;
		owner.username = username;
		owner.persist();
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);

		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "abcdef1234567890abcdef1234567890abcdef12";
		run.status = ActionRun.Status.SUCCESS;
		run.persist();

		ActionTask task = new ActionTask();
		task.run = run;
		task.name = "build";
		task.payload = "on: push";
		task.status = ActionRun.Status.SUCCESS;
		task.logLength = 2;
		task.persist();

		for (int i = 0; i < 2; i++)
		{
			ActionLog log = new ActionLog();
			log.task = task;
			log.lineIndex = i;
			log.content = "line " + i;
			log.persist();
		}
		return new Seed(username);
	}

}
