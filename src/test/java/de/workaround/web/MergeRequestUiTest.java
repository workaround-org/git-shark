package de.workaround.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

@QuarkusTest
class MergeRequestUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	User.Repo userRepo;

	@Test
	@TestSecurity(user = "mru-owner")
	void ownerCanOpenViewDiffAndMergeAMergeRequest()
	{
		User owner = persistUser("mru-owner");
		Repository repo = seed(owner, "board");
		String base = "/repos/" + owner.username + "/board/merge-requests";

		// the new-MR form lets the owner pick source and target branches
		given().when().get(base + "/new")
			.then().statusCode(200)
			.body(containsString("<form"))
			.body(containsString("name=\"sourceBranch\""))
			.body(containsString("name=\"targetBranch\""))
			.body(containsString("feature"));

		// create it
		String location = given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("title", "Ship the feature").formParam("description", "adds feature.txt")
			.formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base)
			.then().statusCode(303)
			.extract().header("Location");

		// list shows it as open
		given().when().get(base)
			.then().statusCode(200)
			.body(containsString("Ship the feature"))
			.body(containsString("Open"));

		// detail shows the diff of the source branch and a merge control
		given().when().get(location)
			.then().statusCode(200)
			.body(containsString("feature.txt"))
			.body(containsString("feature"))
			.body(containsString("Merge"));

		// merge it
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.when().post(location + "/merge")
			.then().statusCode(303);

		given().when().get(location).then().statusCode(200).body(containsString("Merged"));
	}

	@Test
	@TestSecurity(user = "mru-closer")
	void ownerCanCloseAMergeRequestWithoutMerging()
	{
		User owner = persistUser("mru-closer");
		Repository repo = seed(owner, "closeboard");
		String base = "/repos/" + owner.username + "/closeboard/merge-requests";

		String location = given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("title", "Never mind").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base)
			.then().statusCode(303)
			.extract().header("Location");

		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.when().post(location + "/close")
			.then().statusCode(303);

		given().when().get(location).then().statusCode(200).body(containsString("Closed"));
	}

	@Test
	void anonymousCannotCreateMergeRequests()
	{
		User owner = persistUser("mru-anon-" + UUID.randomUUID().toString().substring(0, 8));
		seed(owner, "board");

		given().contentType("application/x-www-form-urlencoded")
			.formParam("title", "Sneaky").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post("/repos/" + owner.username + "/board/merge-requests")
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = "mru-stranger")
	void privateRepositoryMergeRequestsAreHiddenFromStrangers()
	{
		persistUser("mru-stranger");
		User owner = persistUser("mru-owner2-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "secret", Repository.Visibility.PRIVATE, null);

		given().when().get("/repos/" + owner.username + "/secret/merge-requests")
			.then().statusCode(404);
	}

	@Test
	@TestSecurity(user = "mru-count")
	void repoOverviewShowsOpenMergeRequestCount()
	{
		User owner = persistUser("mru-count");
		Repository repo = seed(owner, "counts");
		String base = "/repos/" + owner.username + "/counts/merge-requests";
		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("title", "one").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base).then().statusCode(303);

		given().when().get("/repos/" + owner.username + "/counts")
			.then().statusCode(200)
			.body(containsString("Merge requests"))
			.body(containsString("<span class=\"ct\">1</span>"));
	}

	/** Repo with a base commit on main and a divergent 'feature' branch adding feature.txt. */
	private Repository seed(User owner, String name)
	{
		try
		{
			Repository repo = service.create(owner, name, Repository.Visibility.PUBLIC, null);
			Path bare = service.repositoryPath(repo);
			GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)));
			Path work = Files.createTempDirectory("mruseed");
			try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(work.toFile()).call())
			{
				git.checkout().setCreateBranch(true).setName("feature").call();
				Files.writeString(work.resolve("feature.txt"), "feature\n");
				git.add().addFilepattern(".").call();
				git.commit().setMessage("feature work").setSign(false)
					.setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();
				git.push().setRefSpecs(new RefSpec("feature:refs/heads/feature")).call();
			}
			return repo;
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	@Transactional
	User persistUser(String name)
	{
		User existing = userRepo.findByOidcSubOptional(name).orElse(null);
		if (existing != null)
		{
			return existing;
		}
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.persist();
		return user;
	}
}
