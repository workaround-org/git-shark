package de.workaround.account;

import org.junit.jupiter.api.Test;

import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;

@QuarkusTest
class OnboardingRedirectTest
{
	@Inject
	User.Repo users;

	@Test
	@TestSecurity(user = "needs-onboard")
	void handleLessUserIsRedirectedFromHome()
	{
		seedOnboardingPending("needs-onboard");

		given().redirects().follow(false)
			.when().get("/")
			.then().statusCode(303).header("Location", containsString("/onboarding"));
	}

	@Test
	@TestSecurity(user = "needs-onboard-settings")
	void handleLessUserIsRedirectedFromProtectedPage()
	{
		seedOnboardingPending("needs-onboard-settings");

		given().redirects().follow(false)
			.when().get("/settings/keys")
			.then().statusCode(303).header("Location", containsString("/onboarding"));
	}

	@Test
	@TestSecurity(user = "onboarded-user")
	void onboardedUserIsNotRedirected()
	{
		seedWithHandle("onboarded-user", "onboarded-user");

		given().redirects().follow(false)
			.when().get("/")
			.then().statusCode(200);
	}

	@Test
	@TestSecurity(user = "needs-onboard-page")
	void onboardingPageItselfIsReachable()
	{
		seedOnboardingPending("needs-onboard-page");

		given().redirects().follow(false)
			.when().get("/onboarding")
			.then().statusCode(200).body(containsString("username"));
	}

	@Test
	void anonymousIsNotRedirected()
	{
		given().redirects().follow(false)
			.when().get("/")
			.then().statusCode(equalTo(200));
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
