package de.workaround.api;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.not;

@QuarkusTest
class SearchApiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	User.Repo userRepo;

	@Test
	void searchReturnsRepositoriesAndPersonsAsJson()
	{
		String tok = "apisrch" + shortId();
		User owner = persistUser("owner-" + tok);
		service.create(owner, "repo-" + tok, Repository.Visibility.PUBLIC, null);
		service.create(owner, "priv-" + tok, Repository.Visibility.PRIVATE, null);

		given().when().get("/api/v1/search?q=" + tok)
			.then().statusCode(200)
			.body("repositories.name", hasItem("repo-" + tok))
			.body("repositories.name", not(hasItem("priv-" + tok)))
			.body("persons.username", hasItem("owner-" + tok));
	}

	@Test
	void blankQueryReturnsEmptyArrays()
	{
		given().when().get("/api/v1/search?q=")
			.then().statusCode(200)
			.body("repositories", Matchers.hasSize(0))
			.body("persons", Matchers.hasSize(0));
	}

	private static String shortId()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

	@Transactional
	User persistUser(String name)
	{
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.persist();
		return user;
	}
}
