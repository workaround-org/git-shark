package de.workaround.web;

import java.time.Duration;
import java.util.UUID;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #6 parts A and B: after login the user returns to the page they were on, and expired
 * sessions are refreshed silently via the OIDC refresh token instead of logging the user out.
 */
@QuarkusTest
class LoginRedirectTest
{
	// --- Part A: return to the original page after login ---

	@Test
	void anonymousLoginLinkCarriesCurrentPageAsRedirect() throws Exception
	{
		given()
			.when().get("/explore")
			.then()
			.statusCode(200)
			.body(containsString("href=\"/login?redirect=%2Fexplore\""));
	}

	@Test
	@TestSecurity(user = "login-redirect-user")
	void loginRedirectsToRequestedLocalPath() throws Exception
	{
		persistUser("login-redirect-user");

		given()
			.queryParam("redirect", "/explore")
			.when().redirects().follow(false).get("/login")
			.then()
			.statusCode(303)
			.header("Location", endsWith("/explore"));
	}

	@Test
	@TestSecurity(user = "login-redirect-user")
	void loginRedirectPreservesQueryString() throws Exception
	{
		persistUser("login-redirect-user");

		given()
			.queryParam("redirect", "/explore?tab=all&page=2")
			.when().redirects().follow(false).get("/login")
			.then()
			.statusCode(303)
			.header("Location", endsWith("/explore?tab=all&page=2"));
	}

	@Test
	@TestSecurity(user = "login-redirect-user")
	void loginWithoutRedirectFallsBackToDashboard() throws Exception
	{
		persistUser("login-redirect-user");

		given()
			.when().redirects().follow(false).get("/login")
			.then()
			.statusCode(303)
			.header("Location", endsWith("/"));
	}

	@Test
	@TestSecurity(user = "login-redirect-user")
	void loginRejectsAbsoluteAndProtocolRelativeRedirects() throws Exception
	{
		persistUser("login-redirect-user");

		// "/\t//evil…" covers the control-character class: browsers strip the tab before parsing,
		// which would turn a naive prefix-checked path into a protocol-relative URL
		for (String evil : new String[] { "https://evil.example/phish", "//evil.example/phish",
			"/\\evil.example/phish", "javascript:alert(1)", "/\t//evil.example/phish" })
		{
			given()
				.queryParam("redirect", evil)
				.when().redirects().follow(false).get("/login")
				.then()
				.statusCode(303)
				.header("Location", not(containsString("evil")))
				.header("Location", endsWith("/"));
		}
	}

	// --- Part B: silent session refresh via the OIDC refresh token ---

	@Test
	void silentSessionRefreshIsConfigured() throws Exception
	{
		Config config = ConfigProvider.getConfig();
		assertTrue(config.getValue("quarkus.oidc.token.refresh-expired", Boolean.class),
			"expired ID tokens must be refreshed silently instead of dropping the session");
		assertEquals(Duration.ofHours(12),
			config.getValue("quarkus.oidc.authentication.session-age-extension", Duration.class),
			"session cookie must outlive the short-lived ID token so the refresh can run");
		assertEquals(Duration.ofSeconds(60),
			config.getValue("quarkus.oidc.token.refresh-token-time-skew", Duration.class),
			"tokens must be refreshed proactively shortly before expiry");
	}

	@Test
	void codeFlowCallbackRestoresOriginalPage() throws Exception
	{
		Config config = ConfigProvider.getConfig();
		assertEquals("/login", config.getValue("quarkus.oidc.authentication.redirect-path", String.class),
			"fixed callback path so kanidm's strict redirect_uri matching accepts one registered URI");
		assertTrue(config.getValue("quarkus.oidc.authentication.restore-path-after-redirect", Boolean.class),
			"after the code flow the user must land on the originally requested URL");
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
