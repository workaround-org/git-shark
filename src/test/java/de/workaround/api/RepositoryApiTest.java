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
import static org.hamcrest.CoreMatchers.is;

@QuarkusTest
class RepositoryApiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	AccessTokenService tokenService;

	@Inject
	User.Repo userRepo;

	@Test
	void createListGetAndDeleteARepository()
	{
		User owner = persistUser("api-repo-owner");
		String token = mintToken(owner);

		// create
		String location = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("name", "widgets", "visibility", "PUBLIC", "description", "gadgets"))
			.when().post("/api/v1/repos")
			.then().statusCode(201)
			.body("name", equalTo("widgets"))
			.body("owner", equalTo(owner.username))
			.body("visibility", equalTo("PUBLIC"))
			.body("description", equalTo("gadgets"))
			.extract().header("Location");

		// get via Location
		given().when().get(location)
			.then().statusCode(200)
			.body("name", equalTo("widgets"));

		// list includes it (public, visible even anonymously)
		given().when().get("/api/v1/repos")
			.then().statusCode(200)
			.body("name", org.hamcrest.Matchers.hasItem("widgets"));

		// delete
		given().header("Authorization", "Bearer " + token)
			.when().delete("/api/v1/repos/" + owner.username + "/widgets")
			.then().statusCode(204);

		given().when().get("/api/v1/repos/" + owner.username + "/widgets")
			.then().statusCode(404);
	}

	@Test
	void createRequiresAuthentication()
	{
		given().contentType("application/json")
			.body(Map.of("name", "nope", "visibility", "PUBLIC"))
			.when().post("/api/v1/repos")
			.then().statusCode(401);
	}

	@Test
	void duplicateNameConflicts()
	{
		User owner = persistUser("api-repo-dup");
		String token = mintToken(owner);
		service.create(owner, "dup", Repository.Visibility.PUBLIC, null);

		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("name", "dup", "visibility", "PUBLIC"))
			.when().post("/api/v1/repos")
			.then().statusCode(409);
	}

	@Test
	void invalidNameIsRejected()
	{
		User owner = persistUser("api-repo-badname");
		String token = mintToken(owner);

		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("name", "bad name!", "visibility", "PUBLIC"))
			.when().post("/api/v1/repos")
			.then().statusCode(400);
	}

	@Test
	void privateRepositoryIsHiddenFromStrangers()
	{
		User owner = persistUser("api-repo-priv-" + shortId());
		service.create(owner, "secret", Repository.Visibility.PRIVATE, null);

		// anonymous
		given().when().get("/api/v1/repos/" + owner.username + "/secret")
			.then().statusCode(404);

		// authenticated stranger
		User stranger = persistUser("api-repo-stranger-" + shortId());
		String token = mintToken(stranger);
		given().header("Authorization", "Bearer " + token)
			.when().get("/api/v1/repos/" + owner.username + "/secret")
			.then().statusCode(404);
	}

	@Test
	void nonOwnerCannotDelete()
	{
		User owner = persistUser("api-repo-victim-" + shortId());
		service.create(owner, "keepme", Repository.Visibility.PUBLIC, null);
		User stranger = persistUser("api-repo-thief-" + shortId());
		String token = mintToken(stranger);

		given().header("Authorization", "Bearer " + token)
			.when().delete("/api/v1/repos/" + owner.username + "/keepme")
			.then().statusCode(403);
	}

	@Test
	void unknownBearerTokenIsUnauthorized()
	{
		given().header("Authorization", "Bearer gs_not-a-real-token")
			.contentType("application/json")
			.body(Map.of("name", "x", "visibility", "PUBLIC"))
			.when().post("/api/v1/repos")
			.then().statusCode(401);
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
