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
class SearchUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	User.Repo userRepo;

	@Test
	void resultsPageShowsRepoAndPersonHits()
	{
		String tok = "uisrch" + shortId();
		User owner = persistUser("owner-" + tok);
		service.create(owner, "repo-" + tok, Repository.Visibility.PUBLIC, null);
		service.create(owner, "priv-" + tok, Repository.Visibility.PRIVATE, null);

		given().when().get("/search?q=" + tok)
			.then().statusCode(200)
			.body(containsString("/repos/owner-" + tok + "/repo-" + tok))
			.body(not(containsString("priv-" + tok)))
			.body(containsString("owner-" + tok))
			// person hits link to the profile page (#11)
			.body(containsString("/users/owner-" + tok));
	}

	@Test
	void resultsPageHasNoOwnSearchInputAndPrefillsHeader()
	{
		String tok = "dup" + shortId();
		given().when().get("/search?q=" + tok)
			.then().statusCode(200)
			// the page must not carry its own duplicate search form (class="search")
			.body(not(containsString("class=\"search\"")))
			// the single (header) search box shows what was searched
			.body(containsString("value=\"" + tok + "\""));
	}

	@Test
	void blankQueryRendersPromptWithoutError()
	{
		given().when().get("/search")
			.then().statusCode(200)
			.body(containsString("Type a query to search"));
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
