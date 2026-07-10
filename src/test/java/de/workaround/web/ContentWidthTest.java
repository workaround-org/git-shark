package de.workaround.web;

import org.junit.jupiter.api.Test;

import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

@QuarkusTest
class ContentWidthTest
{
	@Test
	@TestSecurity(user = "width-default-user")
	void profileOffersContentWidthPresetsWithFullAsDefault() throws Exception
	{
		persistUser("width-default-user");

		given()
			.when().get("/settings/profile")
			.then()
			.statusCode(200)
			.body(containsString("name=\"contentWidth\""))
			.body(containsString("value=\"FULL\" selected"))
			.body(containsString("value=\"COMFORTABLE\""))
			.body(containsString("value=\"COMPACT\""));
	}

	@Test
	@TestSecurity(user = "width-compact-user")
	void savedContentWidthAppliesOnEveryPage() throws Exception
	{
		persistUser("width-compact-user");

		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("contentWidth", "COMPACT")
			.when().post("/settings/profile/appearance")
			.then()
			.statusCode(303);

		given()
			.when().get("/explore")
			.then()
			.statusCode(200)
			// the preset class sits on <body> so header nav and content scale together
			.body(containsString("<body class=\"width-compact\">"));

		given()
			.when().get("/settings/profile")
			.then()
			.statusCode(200)
			.body(containsString("value=\"COMPACT\" selected"));
	}

	@Test
	void anonymousVisitorsKeepTheFullWidthDefault() throws Exception
	{
		given()
			.when().get("/explore")
			.then()
			.statusCode(200)
			.body(containsString("<body class=\"\">"))
			.body(not(containsString("width-compact")));
	}

	@Test
	@TestSecurity(user = "width-bogus-user")
	void unknownPresetIsRejected() throws Exception
	{
		persistUser("width-bogus-user");

		given()
			.contentType("application/x-www-form-urlencoded")
			.formParam("contentWidth", "BOGUS")
			.when().post("/settings/profile/appearance")
			.then()
			.statusCode(400);
	}

	@Test
	void stylesheetDefinesTheWidthPresets() throws Exception
	{
		given()
			.when().get("/shark.css")
			.then()
			.statusCode(200)
			// presets live on <body> and drive one shared width variable for header and main;
			// fixed px caps so the column doesn't shift while resizing the window
			.body(containsString("body.width-comfortable"))
			.body(containsString("body.width-compact"))
			.body(containsString("--content-w"))
			.body(containsString("1400px"))
			.body(containsString("1120px"))
			// repo pages escape the default 1400px cap and use the full preset width
			.body(containsString("body:has(.repo-layout)"));
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
