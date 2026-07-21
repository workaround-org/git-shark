package de.workaround.api;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.text.MatchesPattern.matchesPattern;

@QuarkusTest
class BranchApiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	User.Repo userRepo;

	@Test
	void getBranchReturnsGiteaShapeWithCommitId() throws Exception
	{
		User owner = persistUser("api-branch-" + shortId());
		Repository repo = service.create(owner, "brepo", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("f.txt", "x\n".getBytes(StandardCharsets.UTF_8)));

		given().when().get("/api/v1/repos/" + owner.username + "/brepo/branches/main")
			.then().statusCode(200)
			.body("name", equalTo("main"))
			.body("commit.id", matchesPattern("[0-9a-f]{40}"))
			.body("protected", is(false));
	}

	@Test
	void branchNameWithSlashResolves() throws Exception
	{
		User owner = persistUser("api-branch-slash-" + shortId());
		Repository repo = service.create(owner, "brepo", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("f.txt", "x\n".getBytes(StandardCharsets.UTF_8)));
		GitTestSeeder.seedBranch(service.repositoryPath(repo), "feat/x",
			Map.of("g.txt", "y\n".getBytes(StandardCharsets.UTF_8)));

		given().when().get("/api/v1/repos/" + owner.username + "/brepo/branches/feat/x")
			.then().statusCode(200)
			.body("name", equalTo("feat/x"))
			.body("commit.id", matchesPattern("[0-9a-f]{40}"));
	}

	@Test
	void unknownBranchIs404() throws Exception
	{
		User owner = persistUser("api-branch-missing-" + shortId());
		Repository repo = service.create(owner, "brepo", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("f.txt", "x\n".getBytes(StandardCharsets.UTF_8)));

		given().when().get("/api/v1/repos/" + owner.username + "/brepo/branches/nope")
			.then().statusCode(404);
	}

	@Test
	void branchOfPrivateRepoIsHiddenFromStrangers() throws Exception
	{
		User owner = persistUser("api-branch-priv-" + shortId());
		Repository repo = service.create(owner, "secret", Repository.Visibility.PRIVATE, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("f.txt", "x\n".getBytes(StandardCharsets.UTF_8)));

		given().when().get("/api/v1/repos/" + owner.username + "/secret/branches/main")
			.then().statusCode(404);
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
