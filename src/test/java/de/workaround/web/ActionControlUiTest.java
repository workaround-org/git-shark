package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionRun;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The run cancel/re-run controls on the Actions run page (issue #2, phase 2): a writer can drive them
 * over HTTP; a non-writer cannot.
 */
@QuarkusTest
class ActionControlUiTest
{
	private static final String OWNER = "arc-owner";

	private static final String STRANGER = "arc-stranger";

	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo users;

	@Inject
	ActionRun.Repo runs;

	@Test
	@TestSecurity(user = OWNER)
	void ownerCancelsRunningRun()
	{
		Repository repo = repoWithRun("arc-a", ActionRun.Status.RUNNING);

		given().redirects().follow(false)
			.when().post(base(repo) + "/1/cancel")
			.then().statusCode(303);

		assertEquals(ActionRun.Status.CANCELLED, runStatus(repo));
	}

	@Test
	@TestSecurity(user = OWNER)
	void ownerRerunsFinishedRun()
	{
		Repository repo = repoWithRun("arc-b", ActionRun.Status.FAILURE);

		given().redirects().follow(false)
			.when().post(base(repo) + "/1/rerun")
			.then().statusCode(303);

		assertEquals(ActionRun.Status.PENDING, runStatus(repo));
	}

	@Test
	@TestSecurity(user = STRANGER)
	void nonWriterCannotCancel()
	{
		Repository repo = repoWithRun("arc-c", ActionRun.Status.RUNNING);

		given().redirects().follow(false)
			.when().post(base(repo) + "/1/cancel")
			.then().statusCode(403);

		assertEquals(ActionRun.Status.RUNNING, runStatus(repo));
	}

	private static String base(Repository repo)
	{
		return "/repos/" + repo.ownerHandle() + "/" + repo.name + "/actions";
	}

	@Transactional
	ActionRun.Status runStatus(Repository repo)
	{
		return runs.findByRepository(repo).get(0).status;
	}

	private Repository repoWithRun(String repoName, ActionRun.Status status)
	{
		User owner = persistUser(OWNER);
		persistUser(STRANGER);
		return createRepoWithRun(owner, repoName, status);
	}

	@Transactional
	Repository createRepoWithRun(User owner, String repoName, ActionRun.Status status)
	{
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);
		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.status = status;
		run.persist();
		return repo;
	}

	@Transactional
	User persistUser(String name)
	{
		return users.findByOidcSubOptional(name).orElseGet(() ->
		{
			User user = new User();
			user.oidcSub = name;
			user.username = name;
			user.persist();
			return user;
		});
	}
}
