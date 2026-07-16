package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

@QuarkusTest
class ProfileUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	User.Repo userRepo;

	@Test
	void profilePageListsOwnersVisibleRepos()
	{
		String tok = "prof" + shortId();
		User owner = persistUser("person-" + tok, "Person " + tok);
		service.create(owner, "pub-" + tok, Repository.Visibility.PUBLIC, "a public one");
		service.create(owner, "sec-" + tok, Repository.Visibility.PRIVATE, null);

		given().when().get("/users/person-" + tok)
			.then().statusCode(200)
			.body(containsString("Person " + tok))
			.body(containsString("/repos/person-" + tok + "/pub-" + tok))
			.body(not(containsString("sec-" + tok)));
	}

	@Test
	void unknownUserReturns404()
	{
		given().when().get("/users/nope-" + shortId())
			.then().statusCode(404);
	}

	private static String shortId()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

	@Transactional
	User persistUser(String name, String displayName)
	{
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.displayName = displayName;
		user.persist();
		return user;
	}
}
