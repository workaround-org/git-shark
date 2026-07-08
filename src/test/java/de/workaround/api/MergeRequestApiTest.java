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
		String base = "/api/v1/repos/" + owner.username + "/board/merge-requests";

		int number = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "Ship it", "description", "adds feature",
				"sourceBranch", "feature", "targetBranch", "main"))
			.when().post(base)
			.then().statusCode(201)
			.body("title", equalTo("Ship it"))
			.body("status", equalTo("OPEN"))
			.body("sourceBranch", equalTo("feature"))
			.body("targetBranch", equalTo("main"))
			.extract().path("number");

		given().when().get(base)
			.then().statusCode(200)
			.body("title", org.hamcrest.Matchers.hasItem("Ship it"));

		given().when().get(base + "/" + number)
			.then().statusCode(200)
			.body("title", equalTo("Ship it"));

		given().header("Authorization", "Bearer " + token)
			.when().post(base + "/" + number + "/merge")
			.then().statusCode(200)
			.body("status", equalTo("MERGED"));
	}

	@Test
	void openMergeRequestCanBeClosed()
	{
		User owner = persistUser("api-mr-closer");
		String token = mintToken(owner);
		seed(owner, "closeboard");
		String base = "/api/v1/repos/" + owner.username + "/closeboard/merge-requests";

		int number = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "Never mind", "sourceBranch", "feature", "targetBranch", "main"))
			.when().post(base)
			.then().statusCode(201).extract().path("number");

		given().header("Authorization", "Bearer " + token)
			.when().post(base + "/" + number + "/close")
			.then().statusCode(200)
			.body("status", equalTo("CLOSED"));
	}

	@Test
	void unknownBranchIsRejected()
	{
		User owner = persistUser("api-mr-badbranch");
		String token = mintToken(owner);
		seed(owner, "bb");

		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "bad", "sourceBranch", "ghost", "targetBranch", "main"))
			.when().post("/api/v1/repos/" + owner.username + "/bb/merge-requests")
			.then().statusCode(400);
	}

	@Test
	void anonymousCannotCreateMergeRequests()
	{
		User owner = persistUser("api-mr-anon-" + shortId());
		seed(owner, "board");

		given().contentType("application/json")
			.body(Map.of("title", "sneaky", "sourceBranch", "feature", "targetBranch", "main"))
			.when().post("/api/v1/repos/" + owner.username + "/board/merge-requests")
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
