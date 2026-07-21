package de.workaround.api;

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
import de.workaround.http.AccessTokenService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class MergeRequestApiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	AccessTokenService tokenService;

	@Inject
	User.Repo userRepo;

	@Test
	void createListGetAndMergeAMergeRequest()
	{
		User owner = persistUser("api-mr-owner");
		String token = mintToken(owner);
		seed(owner, "board");
		String base = "/api/v1/repos/" + owner.username + "/board/pulls";

		int number = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "Ship it", "body", "adds feature", "head", "feature", "base", "main"))
			.when().post(base)
			.then().statusCode(201)
			.body("title", equalTo("Ship it"))
			.body("state", equalTo("open"))
			.body("merged", is(false))
			.body("head.ref", equalTo("feature"))
			.body("base.ref", equalTo("main"))
			.body("mergeable", is(true))
			.body("assignee", org.hamcrest.Matchers.nullValue())
			.extract().path("number");

		given().when().get(base)
			.then().statusCode(200)
			.body("title", org.hamcrest.Matchers.hasItem("Ship it"));

		given().when().get(base + "/" + number)
			.then().statusCode(200)
			.body("title", equalTo("Ship it"))
			.body("body", equalTo("adds feature"));

		given().header("Authorization", "Bearer " + token)
			.when().post(base + "/" + number + "/merge")
			.then().statusCode(200)
			.body("state", equalTo("closed"))
			.body("merged", is(true));
	}

	@Test
	void openMergeRequestCanBeClosedViaPatch()
	{
		User owner = persistUser("api-mr-closer");
		String token = mintToken(owner);
		seed(owner, "closeboard");
		String base = "/api/v1/repos/" + owner.username + "/closeboard/pulls";

		int number = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "Never mind", "head", "feature", "base", "main"))
			.when().post(base)
			.then().statusCode(201).extract().path("number");

		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("state", "closed"))
			.when().patch(base + "/" + number)
			.then().statusCode(200)
			.body("state", equalTo("closed"))
			.body("merged", is(false));
	}

	@Test
	void closedPullCanBeReopenedViaPatch()
	{
		User owner = persistUser("api-mr-reopener");
		String token = mintToken(owner);
		seed(owner, "reopenboard");
		String base = "/api/v1/repos/" + owner.username + "/reopenboard/pulls";

		int number = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "Later", "head", "feature", "base", "main"))
			.when().post(base)
			.then().statusCode(201).extract().path("number");

		given().header("Authorization", "Bearer " + token).contentType("application/json")
			.body(Map.of("state", "closed")).when().patch(base + "/" + number)
			.then().statusCode(200).body("state", equalTo("closed"));

		given().header("Authorization", "Bearer " + token).contentType("application/json")
			.body(Map.of("state", "open")).when().patch(base + "/" + number)
			.then().statusCode(200)
			.body("state", equalTo("open"))
			.body("merged", is(false));
	}

	@Test
	void mergedPullCannotBeReopened()
	{
		User owner = persistUser("api-mr-nomereopen");
		String token = mintToken(owner);
		seed(owner, "nomereopen");
		String base = "/api/v1/repos/" + owner.username + "/nomereopen/pulls";

		int number = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "Ship", "head", "feature", "base", "main"))
			.when().post(base)
			.then().statusCode(201).extract().path("number");

		given().header("Authorization", "Bearer " + token)
			.when().post(base + "/" + number + "/merge")
			.then().statusCode(200).body("merged", is(true));

		// reopening a merged pull is a no-op: it stays merged/closed
		given().header("Authorization", "Bearer " + token).contentType("application/json")
			.body(Map.of("state", "open")).when().patch(base + "/" + number)
			.then().statusCode(200)
			.body("state", equalTo("closed"))
			.body("merged", is(true));
	}

	@Test
	void patchWithBlankTitleIsRejected()
	{
		User owner = persistUser("api-mr-blanktitle");
		String token = mintToken(owner);
		seed(owner, "blankboard");
		String base = "/api/v1/repos/" + owner.username + "/blankboard/pulls";

		int number = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "Keep", "head", "feature", "base", "main"))
			.when().post(base)
			.then().statusCode(201).extract().path("number");

		given().header("Authorization", "Bearer " + token).contentType("application/json")
			.body(Map.of("title", "   ")).when().patch(base + "/" + number)
			.then().statusCode(400);
	}

	@Test
	void patchUpdatesTitleAndBody()
	{
		User owner = persistUser("api-mr-editor");
		String token = mintToken(owner);
		seed(owner, "editboard");
		String base = "/api/v1/repos/" + owner.username + "/editboard/pulls";

		int number = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "Original", "body", "old", "head", "feature", "base", "main"))
			.when().post(base)
			.then().statusCode(201).extract().path("number");

		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "Renamed", "body", "new"))
			.when().patch(base + "/" + number)
			.then().statusCode(200)
			.body("title", equalTo("Renamed"))
			.body("body", equalTo("new"))
			.body("state", equalTo("open"));
	}

	@Test
	void unknownBranchIsRejected()
	{
		User owner = persistUser("api-mr-badbranch");
		String token = mintToken(owner);
		seed(owner, "bb");

		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "bad", "head", "ghost", "base", "main"))
			.when().post("/api/v1/repos/" + owner.username + "/bb/pulls")
			.then().statusCode(400);
	}

	@Test
	void anonymousCannotCreateMergeRequests()
	{
		User owner = persistUser("api-mr-anon-" + shortId());
		seed(owner, "board");

		given().contentType("application/json")
			.body(Map.of("title", "sneaky", "head", "feature", "base", "main"))
			.when().post("/api/v1/repos/" + owner.username + "/board/pulls")
			.then().statusCode(401);
	}

	/** Repo with a base commit on main and a divergent 'feature' branch adding feature.txt. */
	private Repository seed(User owner, String name)
	{
		try
		{
			Repository repo = service.create(owner, name, Repository.Visibility.PUBLIC, null);
			Path bare = service.repositoryPath(repo);
			GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)));
			Path work = Files.createTempDirectory("apimrseed");
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

	private String mintToken(User user)
	{
		return tokenService.create(user, "api-test").plaintext();
	}

	private static String shortId()
	{
		return UUID.randomUUID().toString().substring(0, 8);
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
