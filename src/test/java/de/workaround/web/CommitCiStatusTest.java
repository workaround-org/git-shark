package de.workaround.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitBrowseService;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
import de.workaround.model.ActionRun;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

/**
 * CI status on commits (issue #2, phase 3): a commit's aggregate run status shows on its page and is
 * exposed through the Gitea commit-status API; a commit with no runs stays all-clear.
 */
@QuarkusTest
class CommitCiStatusTest
{
	@Inject
	GitRepositoryService repositories;

	@Inject
	GitBrowseService browse;

	@Inject
	ActionRun.Repo runs;

	@Inject
	de.workaround.git.MergeRequestService mergeRequests;

	@Inject
	User.Repo users;

	@Test
	void commitPageShowsAggregateBadgeAndApiReflectsFailure()
	{
		Fixture f = seedRepoWithCommit("cs-a");
		seedRun(f.repo, f.head, ActionRun.Status.FAILURE);

		given().when().get("/repos/" + f.owner + "/cs-a/commit/" + f.head)
			.then().statusCode(200)
			.body(containsString("badge status-FAILURE"));

		given().when().get("/api/v1/repos/" + f.owner + "/cs-a/commits/" + f.head + "/status")
			.then().statusCode(200)
			.body("state", equalTo("failure"))
			.body("total_count", equalTo(1));
	}

	@Test
	void successRunShowsSuccessBadge()
	{
		Fixture f = seedRepoWithCommit("cs-b");
		seedRun(f.repo, f.head, ActionRun.Status.SUCCESS);

		given().when().get("/repos/" + f.owner + "/cs-b/commit/" + f.head)
			.then().statusCode(200)
			.body(containsString("badge status-SUCCESS"));
	}

	@Test
	void commitWithoutRunsHasNoBadgeAndApiStaysSuccess()
	{
		Fixture f = seedRepoWithCommit("cs-c");

		given().when().get("/repos/" + f.owner + "/cs-c/commit/" + f.head)
			.then().statusCode(200)
			.body(not(containsString("badge status-")));

		given().when().get("/api/v1/repos/" + f.owner + "/cs-c/commits/" + f.head + "/status")
			.then().statusCode(200)
			.body("state", equalTo("success"));
	}

	@Test
	void mergeRequestPageShowsSourceHeadCiStatus()
	{
		String owner = mergeRequestWithRun("cs-mr", ActionRun.Status.FAILURE);

		given().when().get("/repos/" + owner + "/cs-mr/merge-requests/1")
			.then().statusCode(200)
			.body(containsString("badge status-FAILURE"));
	}

	@Transactional
	String mergeRequestWithRun(String repoName, ActionRun.Status status)
	{
		String username = repoName + "-" + UUID.randomUUID().toString().substring(0, 8);
		User owner = new User();
		owner.oidcSub = username;
		owner.username = username;
		owner.persist();
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);
		try
		{
			Path bare = repositories.repositoryPath(repo);
			GitTestSeeder.seed(bare, Map.of("README.md", "# hi\n".getBytes(StandardCharsets.UTF_8)));
			GitTestSeeder.seedBranch(bare, "feature",
				Map.of("feature.txt", "x\n".getBytes(StandardCharsets.UTF_8)));
			String head = browse.commits(bare, "feature", 0, 1).orElseThrow().commits().get(0).id();
			mergeRequests.create(owner, repo, "add feature", null, "feature", "main");

			ActionRun run = new ActionRun();
			run.repository = repo;
			run.number = runs.maxNumber(repo) + 1;
			run.workflowName = "CI";
			run.workflowFile = ".forgejo/workflows/ci.yml";
			run.event = "push";
			run.ref = "refs/heads/feature";
			run.commitSha = head;
			run.status = status;
			run.persist();
			return username;
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	private record Fixture(String owner, Repository repo, String head)
	{
	}

	private Fixture seedRepoWithCommit(String repoName)
	{
		String username = repoName + "-" + UUID.randomUUID().toString().substring(0, 8);
		Repository repo = createRepo(username, repoName);
		try
		{
			Path bare = repositories.repositoryPath(repo);
			GitTestSeeder.seed(bare, Map.of("README.md", "# hi\n".getBytes(StandardCharsets.UTF_8)));
			String head = browse.commits(bare, "main", 0, 1).orElseThrow().commits().get(0).id();
			return new Fixture(username, repo, head);
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	@Transactional
	Repository createRepo(String username, String repoName)
	{
		User owner = new User();
		owner.oidcSub = username;
		owner.username = username;
		owner.persist();
		return repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);
	}

	@Transactional
	void seedRun(Repository repository, String sha, ActionRun.Status status)
	{
		Repository repo = repositories.find(repository.ownerHandle(), repository.name).orElseThrow();
		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = runs.maxNumber(repo) + 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = sha;
		run.status = status;
		run.persist();
	}
}
