package de.workaround.account;

import org.junit.jupiter.api.Test;

import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class SettingsProfileTest
{
	@Inject
	User.Repo users;

	@Test
	@TestSecurity(user = "profile-view")
	void profilePageShowsCurrentHandle()
	{
		given()
			.when().get("/settings/profile")
			.then().statusCode(200).body(containsString("profile-view"));
	}

	@Test
	@TestSecurity(user = "profile-rename")
	void renamesHandle()
	{
		given().redirects().follow(false)
			.contentType(ContentType.URLENC)
			.formParam("username", "renamed-handle")
			.formParam("displayName", "Renamed")
			.when().post("/settings/profile")
			.then().statusCode(anyOf(is(302), is(303)));

		assertEquals("renamed-handle", bySub("profile-rename").username);
		assertEquals("Renamed", bySub("profile-rename").displayName);
	}

	@Test
	@TestSecurity(user = "profile-collide")
	void rejectsRenameToTakenHandle()
	{
		seedWithHandle("profile-collide-owner", "profile-collide-taken");

		given().redirects().follow(false)
			.contentType(ContentType.URLENC)
			.formParam("username", "profile-collide-taken")
			.formParam("displayName", "X")
			.when().post("/settings/profile")
			.then().statusCode(400);

		assertEquals("profile-collide", bySub("profile-collide").username, "handle unchanged after collision");
	}

	private User bySub(String sub)
	{
		return users.findByOidcSub(sub);
	}

	@Transactional
	void seedWithHandle(String sub, String handle)
	{
		if (users.findByOidcSubOptional(sub).isPresent())
		{
			return;
		}
		User user = new User();
		user.oidcSub = sub;
		user.username = handle;
		user.persist();
	}
}
