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
import static org.hamcrest.Matchers.hasSize;

@QuarkusTest
class LabelStatusApiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	AccessTokenService tokenService;

	@Inject
	User.Repo userRepo;

	@Test
	void labelsAreAnEmptyList()
	{
		User owner = persistUser("api-labels-" + shortId());
		service.create(owner, "lrepo", Repository.Visibility.PUBLIC, null);

		given().when().get("/api/v1/repos/" + owner.username + "/lrepo/labels")
			.then().statusCode(200)
			.body("$", hasSize(0));
	}

	@Test
	void combinedStatusIsAllClear()
	{
		User owner = persistUser("api-status-" + shortId());
		service.create(owner, "srepo", Repository.Visibility.PUBLIC, null);

		given().when().get("/api/v1/repos/" + owner.username + "/srepo/commits/main/status")
			.then().statusCode(200)
			.body("state", equalTo("success"))
			.body("statuses", hasSize(0));
	}

	@Test
	void combinedStatusResolvesSlashBearingRef()
	{
		User owner = persistUser("api-status-slash-" + shortId());
		service.create(owner, "srepo", Repository.Visibility.PUBLIC, null);

		given().when().get("/api/v1/repos/" + owner.username + "/srepo/commits/renovate/foo/status")
			.then().statusCode(200)
			.body("state", equalTo("success"));
	}

	@Test
	void statusListIsEmpty()
	{
		User owner = persistUser("api-statuslist-" + shortId());
		service.create(owner, "srepo", Repository.Visibility.PUBLIC, null);

		given().when().get("/api/v1/repos/" + owner.username + "/srepo/commits/main/statuses")
			.then().statusCode(200)
			.body("$", hasSize(0));
	}

	@Test
	void postingAStatusEchoesItBack()
	{
		User owner = persistUser("api-poststatus-" + shortId());
		String token = tokenService.create(owner, "api-test").plaintext();
		service.create(owner, "srepo", Repository.Visibility.PUBLIC, null);

		given().header("Authorization", "Bearer " + token).contentType("application/json")
			.body(Map.of("state", "success", "context", "ci/build", "description", "ok"))
			.when().post("/api/v1/repos/" + owner.username + "/srepo/statuses/deadbeef")
			.then().statusCode(201)
			.body("state", equalTo("success"))
			.body("context", equalTo("ci/build"));
	}

	@Test
	void postingAStatusRequiresAuthentication()
	{
		User owner = persistUser("api-poststatus-noauth-" + shortId());
		service.create(owner, "srepo", Repository.Visibility.PUBLIC, null);

		given().contentType("application/json")
			.body(Map.of("state", "success", "context", "ci/build"))
			.when().post("/api/v1/repos/" + owner.username + "/srepo/statuses/deadbeef")
			.then().statusCode(401);
	}

	@Test
	void postingAStatusAsNonWriterIsForbidden()
	{
		User owner = persistUser("api-poststatus-owner-" + shortId());
		service.create(owner, "srepo", Repository.Visibility.PUBLIC, null);
		User stranger = persistUser("api-poststatus-stranger-" + shortId());
		String token = tokenService.create(stranger, "api-test").plaintext();

		given().header("Authorization", "Bearer " + token).contentType("application/json")
			.body(Map.of("state", "success", "context", "ci/build"))
			.when().post("/api/v1/repos/" + owner.username + "/srepo/statuses/deadbeef")
			.then().statusCode(403);
	}

	@Test
	void labelsOfPrivateRepoHiddenFromStrangers()
	{
		User owner = persistUser("api-labels-priv-" + shortId());
		service.create(owner, "secret", Repository.Visibility.PRIVATE, null);

		given().when().get("/api/v1/repos/" + owner.username + "/secret/labels")
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
