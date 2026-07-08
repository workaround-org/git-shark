package de.workaround.account;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class SettingsPagesTest
{
	private static final String VALID_KEY = de.workaround.ssh.TestKeys.validOpenSshKey();

	@Test
	@TestSecurity(user = "settings-tester")
	void sshKeyPageListsAndAddsKeys()
	{
		given()
			.when().get("/settings/keys")
			.then()
			.statusCode(200)
			.body(containsString("SSH keys"));

		given()
			.redirects().follow(false)
			.formParam("title", "my laptop")
			.formParam("key", VALID_KEY)
			.when().post("/settings/keys")
			.then()
			.statusCode(anyOf(is(302), is(303)));

		given()
			.when().get("/settings/keys")
			.then()
			.statusCode(200)
			.body(containsString("my laptop"));
	}

	@Test
	@TestSecurity(user = "settings-tester")
	void rejectsInvalidSshKeyWithMessage()
	{
		given()
			.formParam("title", "broken")
			.formParam("key", "garbage")
			.when().post("/settings/keys")
			.then()
			.statusCode(400)
			.body(containsString("Not a valid OpenSSH public key"));
	}

	@Test
	@TestSecurity(user = "token-tester")
	void tokenCreatedPageShowsPlaintextExactlyOnce()
	{
		String body = given()
			.formParam("label", "ci-token")
			.when().post("/settings/tokens")
			.then()
			.statusCode(200)
			.body(containsString("gs_"))
			.extract().body().asString();

		String plaintext = body.substring(body.indexOf("gs_"));
		plaintext = plaintext.substring(0, plaintext.indexOf('<'));

		given()
			.when().get("/settings/tokens")
			.then()
			.statusCode(200)
			.body(containsString("ci-token"))
			.body(not(containsString(plaintext)));
	}

	@Test
	@TestSecurity(user = "token-copy-tester")
	void tokenCreatedPageHasCopyButton()
	{
		given()
			.formParam("label", "copy-token")
			.when().post("/settings/tokens")
			.then()
			.statusCode(200)
			.body(containsString("copy-btn"))
			.body(containsString("data-copy=\"gs_"));
	}

	@Test
	void anonymousIsRedirectedToLogin()
	{
		given()
			.redirects().follow(false)
			.when().get("/settings/keys")
			.then()
			.statusCode(302);
	}

}
