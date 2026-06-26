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
class DesignSystemTest
{
	@Inject
	GitRepositoryService service;

	@Test
	void pagesLinkSharedStylesheetAndHaveNoInlineStyles()
	{
		given().when().get("/")
			.then().statusCode(200)
			.body(containsString("<link rel=\"stylesheet\" href=\"/shark.css\">"))
			.body(not(containsString("<style>")));
	}

	@Test
	void sharedStylesheetDefinesSharkTokens()
	{
		given().when().get("/shark.css")
			.then().statusCode(200)
			.body(containsString(":root"))
			.body(containsString("--accent"))
			.body(containsString("--ink"))
			.body(containsString("--canvas"));
	}

	@Test
	void landingPageShowsLogoAndBrandedHeader()
	{
		given().when().get("/")
			.then().statusCode(200)
			.body(containsString("landing-mark"))
			.body(containsString("git-shark"));
	}

	@Test
	void faviconIsServedAsSvgResource()
	{
		given().when().get("/favicon.svg")
			.then().statusCode(200)
			.header("Content-Type", containsString("image/svg+xml"));
	}

	@Test
	void explorePageShowsVisibilityBadges()
	{
		User owner = persistUser("ds-owner-" + unique());
		service.create(owner, "ds-badged", Repository.Visibility.PUBLIC, "demo");

		given().when().get("/explore")
			.then().statusCode(200)
			.body(containsString("class=\"badge"));
	}

	@Test
	void layoutContainsHotkeyHelpDialog()
	{
		given().when().get("/")
			.then().statusCode(200)
			.body(containsString("<dialog id=\"hotkey-help\""))
			.body(containsString("/shark-hotkeys.js"));
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

	@Inject
	User.Repo userRepo;
}
