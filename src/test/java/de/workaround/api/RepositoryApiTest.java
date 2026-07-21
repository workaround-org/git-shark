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

		// create — response is in the Gitea repository shape
		String location = given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("name", "widgets", "visibility", "PUBLIC", "description", "gadgets"))
			.when().post("/api/v1/repos")
			.then().statusCode(201)
			.body("name", equalTo("widgets"))
			.body("full_name", equalTo(owner.username + "/widgets"))
			.body("owner.login", equalTo(owner.username))
			.body("private", is(false))
			.body("description", equalTo("gadgets"))
			.body("default_branch", equalTo("main"))
			.body("default_merge_style", equalTo("merge"))
			.body("clone_url", org.hamcrest.Matchers.endsWith("/git/" + owner.username + "/widgets.git"))
			.body("permissions.admin", is(true))
			.extract().header("Location");

		// get via Location
		given().when().get(location)
			.then().statusCode(200)
			.body("name", equalTo("widgets"))
			.body("full_name", equalTo(owner.username + "/widgets"));

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
	void forkCreatesRepositoryUnderCallerWithParentRecorded()
	{
		User owner = persistUser("api-fork-src-" + shortId());
		service.create(owner, "tocopy", Repository.Visibility.PUBLIC, "original");
		User forker = persistUser("api-forker-" + shortId());
		String token = mintToken(forker);

		given().header("Authorization", "Bearer " + token)
			.when().post("/api/v1/repos/" + owner.username + "/tocopy/fork")
			.then().statusCode(201)
			.body("owner.login", equalTo(forker.username))
			.body("name", equalTo("tocopy"))
			.body("fork", is(true))
			.body("parent.owner.login", equalTo(owner.username))
			.body("parent.name", equalTo("tocopy"));
	}

	@Test
	void forkStopsExposingParentAfterSourceTurnsPrivate()
	{
		User owner = persistUser("api-flip-owner-" + shortId());
		Repository source = service.create(owner, "flip", Repository.Visibility.PUBLIC, null);
		User forker = persistUser("api-flip-forker-" + shortId());
		String token = mintToken(forker);

		given().header("Authorization", "Bearer " + token)
			.when().post("/api/v1/repos/" + owner.username + "/flip/fork")
			.then().statusCode(201)
			.body("parent.owner.login", equalTo(owner.username));

		// owner makes the source private; the forker can no longer read it
		service.changeVisibility(owner, source, Repository.Visibility.PRIVATE);

		given().header("Authorization", "Bearer " + token)
			.when().get("/api/v1/repos/" + forker.username + "/flip")
			.then().statusCode(200)
			.body("fork", is(true))
			.body("parent", org.hamcrest.Matchers.nullValue());
	}

	@Test
	void forkRequiresAuthentication()
	{
		User owner = persistUser("api-fork-noauth-" + shortId());
		service.create(owner, "pub", Repository.Visibility.PUBLIC, null);

		given().when().post("/api/v1/repos/" + owner.username + "/pub/fork")
			.then().statusCode(401);
	}

	@Test
	void forkingAnUnreadablePrivateRepositoryIs404()
	{
		User owner = persistUser("api-fork-priv-" + shortId());
		service.create(owner, "secret", Repository.Visibility.PRIVATE, null);
		User stranger = persistUser("api-fork-stranger-" + shortId());
		String token = mintToken(stranger);

		given().header("Authorization", "Bearer " + token)
			.when().post("/api/v1/repos/" + owner.username + "/secret/fork")
			.then().statusCode(404);
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
