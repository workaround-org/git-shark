package de.workaround.api;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.http.AccessTokenService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

@QuarkusTest
class IssueApiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	AccessTokenService tokenService;

	@Inject
	User.Repo userRepo;

	@Test
	void createListGetUpdateAndDeleteAnIssue()
	{
		User owner = persistUser("api-issue-owner");
		String token = mintToken(owner);
		service.create(owner, "tracker", Repository.Visibility.PUBLIC, null);
		String base = "/api/v1/repos/" + owner.username + "/tracker/issues";

		// create
		int number = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "First bug", "description", "boom"))
			.when().post(base)
			.then().statusCode(201)
			.body("title", equalTo("First bug"))
			.body("status", equalTo("PLANNED"))
			.body("author", equalTo(owner.username))
			.body("assignee", org.hamcrest.Matchers.nullValue())
			.extract().path("number");

		// list
		given().when().get(base)
			.then().statusCode(200)
			.body("title", org.hamcrest.Matchers.hasItem("First bug"));

		// get by number
		given().when().get(base + "/" + number)
			.then().statusCode(200)
			.body("title", equalTo("First bug"));

		// update status
		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("status", "DONE"))
			.when().patch(base + "/" + number)
			.then().statusCode(200)
			.body("status", equalTo("DONE"));

		// delete
		given().header("Authorization", "Bearer " + token)
			.when().delete(base + "/" + number)
			.then().statusCode(204);

		given().when().get(base + "/" + number)
			.then().statusCode(404);
	}

	@Test
	void anonymousCannotCreateIssues()
	{
		User owner = persistUser("api-issue-anon-" + shortId());
		service.create(owner, "tracker", Repository.Visibility.PUBLIC, null);

		given().contentType("application/json")
			.body(Map.of("title", "sneaky"))
			.when().post("/api/v1/repos/" + owner.username + "/tracker/issues")
			.then().statusCode(401);
	}

	@Test
	void strangerCannotCreateIssues()
	{
		User owner = persistUser("api-issue-owner2-" + shortId());
		service.create(owner, "tracker", Repository.Visibility.PUBLIC, null);
		User stranger = persistUser("api-issue-stranger-" + shortId());
		String token = mintToken(stranger);

		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "sneaky"))
			.when().post("/api/v1/repos/" + owner.username + "/tracker/issues")
			.then().statusCode(403);
	}

	@Test
	void blankTitleIsRejected()
	{
		User owner = persistUser("api-issue-blank-" + shortId());
		String token = mintToken(owner);
		service.create(owner, "tracker", Repository.Visibility.PUBLIC, null);

		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("title", "   "))
			.when().post("/api/v1/repos/" + owner.username + "/tracker/issues")
			.then().statusCode(400);
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
