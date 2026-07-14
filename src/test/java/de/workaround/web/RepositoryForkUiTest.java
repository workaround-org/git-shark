package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

@QuarkusTest
class RepositoryForkUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	User.Repo userRepo;

	@Test
	@TestSecurity(user = "forker-ui")
	void forkingRedirectsToTheForkWhichShowsForkedFrom()
	{
		persistUser("forker-ui");
		User owner = persistUser("fork-owner-" + unique());
		service.create(owner, "tofork", Repository.Visibility.PUBLIC, null);

		given().redirects().follow(false)
			.contentType(ContentType.URLENC)
			.when().post("/repos/" + owner.username + "/tofork/fork")
			.then().statusCode(303)
			.header("Location", containsString("/repos/forker-ui/tofork"));

		given().when().get("/repos/forker-ui/tofork")
			.then().statusCode(200)
			.body(containsString("forked from"))
			.body(containsString("/repos/" + owner.username + "/tofork"));
	}

	@Test
	@TestSecurity(user = "flip-forker")
	void forkHidesForkedFromAfterSourceTurnsPrivate()
	{
		persistUser("flip-forker");
		User owner = persistUser("flip-owner-" + unique());
		Repository source = service.create(owner, "flipui", Repository.Visibility.PUBLIC, null);

		given().redirects().follow(false)
			.contentType(ContentType.URLENC)
			.when().post("/repos/" + owner.username + "/flipui/fork")
			.then().statusCode(303);

		given().when().get("/repos/flip-forker/flipui")
			.then().statusCode(200).body(containsString("forked from"));

		// owner makes the source private — the fork must stop revealing where it came from
		service.changeVisibility(owner, source, Repository.Visibility.PRIVATE);

		given().when().get("/repos/flip-forker/flipui")
			.then().statusCode(200).body(not(containsString("forked from")));
	}

	@Test
	void anonymousCannotFork()
	{
		User owner = persistUser("fork-anon-" + unique());
		service.create(owner, "pub", Repository.Visibility.PUBLIC, null);

		given().redirects().follow(false)
			.contentType(ContentType.URLENC)
			.when().post("/repos/" + owner.username + "/pub/fork")
			.then().statusCode(403);
	}

	private static String unique()
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
