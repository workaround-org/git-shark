package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.account.OrganisationService;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Organisation;
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
class DashboardTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	de.workaround.git.IssueService issueService;

	@Inject
	User.Repo userRepo;

	@Inject
	OrganisationService organisations;

	@Test
	@TestSecurity(user = "dash-alice")
	void authenticatedHomeRendersThreeSections()
	{
		User alice = persistUser("dash-alice");
		service.create(alice, "dash-repo-" + unique(), Repository.Visibility.PRIVATE, null);

		given()
			.when().get("/")
			.then()
			.statusCode(200)
			.body(containsString("Pinned"))
			.body(containsString("Notifications"))
			.body(containsString("All repositories"))
			// notifications empty state when the user is not involved in anything yet
			.body(containsString("Nothing needs your attention"));
	}

	@Test
	@TestSecurity(user = "dash-notif")
	void issuesTheUserIsInvolvedInAppearInNotifications()
	{
		User user = persistUser("dash-notif");
		Repository repo = service.create(user, "notifrepo", Repository.Visibility.PUBLIC, null);
		issueService.create(user, repo, "Please look at this", null);

		given().when().get("/").then().statusCode(200)
			.body(containsString("Notifications"))
			.body(containsString("Please look at this"))
			.body(not(containsString("Nothing needs your attention")));
	}

	@Test
	@TestSecurity(user = "dash-order")
	void notificationsSectionAppearsBeforeOrganisations()
	{
		User user = persistUser("dash-order");
		createOrg(user);

		String body = given()
			.when().get("/")
			.then().statusCode(200)
			.extract().body().asString();

		int notifications = body.indexOf("<h2>Notifications</h2>");
		int organisations = body.indexOf("<h2>Organisations</h2>");
		org.junit.jupiter.api.Assertions.assertTrue(notifications >= 0, "Notifications section missing");
		org.junit.jupiter.api.Assertions.assertTrue(organisations >= 0, "Organisations section missing");
		org.junit.jupiter.api.Assertions.assertTrue(notifications < organisations,
			"Notifications section must come before Organisations");
	}

	@Test
	@TestSecurity(user = "dash-pin")
	void pinningARepositoryReflectsInThePinnedSection()
	{
		User user = persistUser("dash-pin");
		service.create(user, "pinme", Repository.Visibility.PUBLIC, null);

		given().when().get("/").then().statusCode(200)
			.body(containsString("No pinned repositories yet"));

		given().redirects().follow(false)
			.contentType(ContentType.URLENC).formParam("redirect", "/")
			.when().post("/repos/dash-pin/pinme/pin")
			.then().statusCode(303);

		given().when().get("/").then().statusCode(200)
			.body(not(containsString("No pinned repositories yet")))
			.body(containsString("/repos/dash-pin/pinme/unpin"))
			.body(containsString("Unpin repository"))
			// pinned and all-repositories rows are full-row click targets (stretched link)
			.body(containsString("class=\"row-link\""))
			.body(containsString("class=\"cell-link\""));

		given().redirects().follow(false)
			.contentType(ContentType.URLENC).formParam("redirect", "/")
			.when().post("/repos/dash-pin/pinme/unpin")
			.then().statusCode(303);

		given().when().get("/").then().statusCode(200)
			.body(containsString("No pinned repositories yet"));
	}

	@Test
	@TestSecurity(user = "dash-stranger")
	void pinningANonVisibleRepositoryIsRejected()
	{
		persistUser("dash-stranger");
		User owner = persistUser("dash-owner-" + unique());
		service.create(owner, "secret", Repository.Visibility.PRIVATE, null);

		given().redirects().follow(false)
			.contentType(ContentType.URLENC).formParam("redirect", "/")
			.when().post("/repos/" + owner.username + "/secret/pin")
			.then().statusCode(404);
	}

	@Test
	void anonymousCannotPin()
	{
		User owner = persistUser("dash-anon-" + unique());
		service.create(owner, "anon-pub", Repository.Visibility.PUBLIC, null);

		given().redirects().follow(false)
			.contentType(ContentType.URLENC).formParam("redirect", "/")
			.when().post("/repos/" + owner.username + "/anon-pub/pin")
			.then().statusCode(403);
	}

	@Test
	void anonymousHomeStillServesLandingPage()
	{
		given()
			.when().get("/")
			.then()
			.statusCode(200)
			.body(containsString("Use AI as a tool, not as a feature"));
	}

	private static String unique()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

	@Transactional
	Organisation createOrg(User creator)
	{
		return organisations.create(creator, "dashorg-" + unique(), null);
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
