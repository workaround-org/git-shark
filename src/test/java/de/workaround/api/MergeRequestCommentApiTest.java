package de.workaround.api;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.MergeRequestService;
import de.workaround.git.GitTestSeeder;
import de.workaround.http.AccessTokenService;
import de.workaround.model.MergeRequest;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

@QuarkusTest
class MergeRequestCommentApiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	MergeRequestService mergeRequestService;

	@Inject
	AccessTokenService tokenService;

	@Inject
	User.Repo userRepo;

	@Test
	void readerCanCommentListAndDeleteOwnComment()
	{
		User owner = persistUser("api-c-owner");
		int number = seedRepoWithMergeRequest(owner, "cboard");
		String base = "/api/v1/repos/" + owner.username + "/cboard/pulls/" + number + "/comments";

		// a different authenticated user (reader of the public repo) can comment
		User reader = persistUser("api-c-reader");
		String readerToken = mintToken(reader);

		String commentId = given().header("Authorization", "Bearer " + readerToken)
			.contentType("application/json")
			.body(anchor("looks good"))
			.when().post(base)
			.then().statusCode(201)
			.body("body", equalTo("looks good"))
			.body("author", equalTo(reader.username))
			.body("filePath", equalTo("feature.txt"))
			.extract().path("id");

		// list shows it (public read, even anonymously)
		given().when().get(base)
			.then().statusCode(200)
			.body("body", org.hamcrest.Matchers.hasItem("looks good"));

		// author deletes it
		given().header("Authorization", "Bearer " + readerToken)
			.when().delete(base + "/" + commentId)
			.then().statusCode(204);
	}

	@Test
	void anonymousCannotComment()
	{
		User owner = persistUser("api-c-anon-" + shortId());
		int number = seedRepoWithMergeRequest(owner, "cboard");

		given().contentType("application/json")
			.body(anchor("sneaky"))
			.when().post("/api/v1/repos/" + owner.username + "/cboard/pulls/" + number + "/comments")
			.then().statusCode(401);
	}

	@Test
	void commentMustAnchorToADiffLine()
	{
		User owner = persistUser("api-c-anchor-" + shortId());
		String token = mintToken(owner);
		int number = seedRepoWithMergeRequest(owner, "cboard");

		Map<String, Object> body = new HashMap<>();
		body.put("filePath", "feature.txt");
		body.put("oldLine", 999);
		body.put("newLine", 999);
		body.put("body", "nowhere");

		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(body)
			.when().post("/api/v1/repos/" + owner.username + "/cboard/pulls/" + number + "/comments")
			.then().statusCode(400);
	}

	@Test
	void repositoryOwnerCanDeleteAnotherUsersComment()
	{
		User owner = persistUser("api-c-owner2-" + shortId());
		String ownerToken = mintToken(owner);
		int number = seedRepoWithMergeRequest(owner, "cboard");
		String base = "/api/v1/repos/" + owner.username + "/cboard/pulls/" + number + "/comments";

		User reader = persistUser("api-c-reader2-" + shortId());
		String readerToken = mintToken(reader);
		String commentId = given().header("Authorization", "Bearer " + readerToken)
			.contentType("application/json").body(anchor("hi"))
			.when().post(base).then().statusCode(201).extract().path("id");

		given().header("Authorization", "Bearer " + ownerToken)
			.when().delete(base + "/" + commentId)
			.then().statusCode(204);
	}

	@Test
	void strangerCannotDeleteAnothersComment()
	{
		User owner = persistUser("api-c-owner3-" + shortId());
		int number = seedRepoWithMergeRequest(owner, "cboard");
		String base = "/api/v1/repos/" + owner.username + "/cboard/pulls/" + number + "/comments";

		User reader = persistUser("api-c-reader3-" + shortId());
		String readerToken = mintToken(reader);
		String commentId = given().header("Authorization", "Bearer " + readerToken)
			.contentType("application/json").body(anchor("mine"))
			.when().post(base).then().statusCode(201).extract().path("id");

		User stranger = persistUser("api-c-stranger-" + shortId());
		String strangerToken = mintToken(stranger);
		given().header("Authorization", "Bearer " + strangerToken)
			.when().delete(base + "/" + commentId)
			.then().statusCode(403);
	}

	/** An anchor on the single added line of feature.txt (added lines have no old-side number). */
	private static Map<String, Object> anchor(String body)
	{
		Map<String, Object> map = new HashMap<>();
		map.put("filePath", "feature.txt");
		map.put("oldLine", -1);
		map.put("newLine", 1);
		map.put("body", body);
		return map;
	}

	/** Public repo with a base commit on main and a 'feature' branch adding feature.txt, plus an open MR for it. */
	private int seedRepoWithMergeRequest(User owner, String name)
	{
		try
		{
			Repository repo = service.create(owner, name, Repository.Visibility.PUBLIC, null);
			Path bare = service.repositoryPath(repo);
			GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)));
			Path work = Files.createTempDirectory("apicseed");
			try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(work.toFile()).call())
			{
				git.checkout().setCreateBranch(true).setName("feature").call();
				Files.writeString(work.resolve("feature.txt"), "feature\n");
				git.add().addFilepattern(".").call();
				git.commit().setMessage("feature work").setSign(false)
					.setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();
				git.push().setRefSpecs(new RefSpec("feature:refs/heads/feature")).call();
			}
			MergeRequest mr = mergeRequestService.create(owner, repo, "Review me", null, "feature", "main");
			return mr.number;
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
