package de.workaround.account;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * Local logout: kanidm advertises no end_session_endpoint, so Quarkus RP-Initiated Logout is disabled.
 * /logout must instead clear the local OIDC session and redirect home, so the template's Logout link works.
 */
@QuarkusTest
class LogoutResourceTest
{
	@Test
	void logoutClearsSessionAndRedirectsHome()
	{
		given()
			.redirects().follow(false)
			.when().post("/logout")
			.then()
			.statusCode(anyOf(is(302), is(303)))
			.header("Location", endsWith("/"));
	}
}
