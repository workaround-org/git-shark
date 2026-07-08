package de.workaround.web;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.RemoteActor;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;

/** The /following page: follow a remote repository by handle/URL, list follows, unfollow. */
@QuarkusTest
class FollowingPagesTest
{
	@Test
	@TestSecurity(user = "following-tester")
	void followListAndUnfollowRoundTrip()
	{
		String remote = "https://peer.test/ap/repos/bob/lib-" + unique();
		seedRemoteActor(remote, remote + "/inbox");

		given()
			.when().get("/following")
			.then()
			.statusCode(200)
			.body(containsString("Follow remote repository"));

		given()
			.redirects().follow(false)
			.formParam("handle", remote)
			.when().post("/following")
			.then()
			.statusCode(anyOf(is(302), is(303)));

		String body = given()
			.when().get("/following")
			.then()
			.statusCode(200)
			.body(containsString(remote))
			.body(containsString("Pending"))
			.extract().body().asString();

		String followId = body.substring(body.indexOf("/following/") + "/following/".length());
		followId = followId.substring(0, followId.indexOf("/unfollow"));

		given()
			.redirects().follow(false)
			.when().post("/following/" + followId + "/unfollow")
			.then()
			.statusCode(anyOf(is(302), is(303)));

		given()
			.when().get("/following")
			.then()
			.statusCode(200)
			.body(not(containsString(remote)));
	}

	@Test
	@TestSecurity(user = "following-err-tester")
	void unresolvableHandleShowsError()
	{
		given()
			.formParam("handle", "https://not-allowlisted.test/ap/repos/x/y")
			.when().post("/following")
			.then()
			.statusCode(400)
			.body(containsString("Could not resolve"));
	}

	@Test
	void anonymousIsRedirectedToLogin()
	{
		given()
			.redirects().follow(false)
			.when().get("/following")
			.then()
			.statusCode(302);
	}

	@Transactional
	void seedRemoteActor(String actorId, String inbox)
	{
		RemoteActor actor = new RemoteActor();
		actor.actorId = actorId;
		actor.inbox = inbox;
		actor.publicKeyPem = "-----BEGIN PUBLIC KEY-----\nstub\n-----END PUBLIC KEY-----";
		actor.fetchedAt = Instant.now();
		actor.persist();
	}

	private static String unique()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
