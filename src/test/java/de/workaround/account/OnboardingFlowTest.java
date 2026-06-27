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
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
class OnboardingFlowTest
{
	@Inject
	User.Repo users;

	@Test
	@TestSecurity(user = "flow-valid")
	void validHandleIsStoredAndRedirectsHome()
	{
		seedOnboardingPending("flow-valid");

		given().redirects().follow(false)
			.contentType(ContentType.URLENC)
			.formParam("username", "flow-handle")
			.formParam("displayName", "Flow User")
			.when().post("/onboarding")
			.then().statusCode(303).header("Location", containsString("/"));

		assertEquals("flow-handle", findBySub("flow-valid").username);
		assertEquals("Flow User", findBySub("flow-valid").displayName);
	}

	@Test
	@TestSecurity(user = "flow-invalid")
	void invalidHandleIsRejected()
	{
		seedOnboardingPending("flow-invalid");

		given().redirects().follow(false)
			.contentType(ContentType.URLENC)
			.formParam("username", "Bad.Handle")
			.formParam("displayName", "X")
			.when().post("/onboarding")
			.then().statusCode(400);
	}

	@Test
	@TestSecurity(user = "flow-taken")
	void takenHandleIsRejected()
	{
		seedOnboardingPending("flow-taken");
		seedWithHandle("flow-taken-owner", "flow-taken-handle");

		given().redirects().follow(false)
			.contentType(ContentType.URLENC)
			.formParam("username", "flow-taken-handle")
			.formParam("displayName", "X")
			.when().post("/onboarding")
			.then().statusCode(400);
	}

	@Test
	@TestSecurity(user = "flow-already")
	void onboardedUserVisitingOnboardingIsRedirectedHome()
	{
		seedWithHandle("flow-already", "flow-already");

		given().redirects().follow(false)
			.when().get("/onboarding")
			.then().statusCode(303).header("Location", containsString("/"));
	}

	private User findBySub(String sub)
	{
		return users.findByOidcSub(sub);
	}

	@Transactional
	void seedOnboardingPending(String sub)
	{
		if (users.findByOidcSubOptional(sub).isPresent())
		{
			return;
		}
		User user = new User();
		user.oidcSub = sub;
		user.persist();
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
