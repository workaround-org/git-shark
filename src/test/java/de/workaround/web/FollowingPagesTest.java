package de.workaround.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.federation.ActivityDispatcher;
import de.workaround.federation.RemoteRepositoryDirectory;
import de.workaround.model.RemoteActor;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
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
	@Inject
	ActivityDispatcher dispatcher;

	@Inject
	ObjectMapper mapper;

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
	@TestSecurity(user = "push-feed-tester")
	void receivedPushesFromFollowedRepoAppearOnPage()
	{
		String remote = "https://peer.test/ap/repos/bob/feed-" + unique();
		seedRemoteActor(remote, remote + "/inbox");

		given()
			.redirects().follow(false)
			.formParam("handle", remote)
			.when().post("/following")
			.then()
			.statusCode(anyOf(is(302), is(303)));

		dispatchPush(remote, "Pushed 3 commit(s) to refs/heads/main");

		given()
			.when().get("/following")
			.then()
			.statusCode(200)
			.body(containsString("Recent pushes"))
			.body(containsString("Pushed 3 commit(s) to refs/heads/main"));
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
	@TestSecurity(user = "user-follow-tester")
	void followUserGroupsRepositoriesAndUnfollows()
	{
		String person = "https://peer.test/ap/users/bob-" + unique();
		String repo = "https://peer.test/ap/repos/bob/lib-" + unique();
		seedRemoteActor(repo, repo + "/inbox");
		QuarkusMock.installMockForType(new RemoteRepositoryDirectory()
		{
			@Override
			public List<String> repositoriesOf(String personActorId)
			{
				return person.equals(personActorId) ? List.of(repo) : List.of();
			}
		}, RemoteRepositoryDirectory.class);

		given()
			.when().get("/following")
			.then()
			.statusCode(200)
			.body(containsString("Follow remote user"));

		given()
			.redirects().follow(false)
			.formParam("handle", person)
			.when().post("/following/users")
			.then()
			.statusCode(anyOf(is(302), is(303)));

		String body = given()
			.when().get("/following")
			.then()
			.statusCode(200)
			.body(containsString(person))
			.body(containsString(repo))
			.extract().body().asString();

		String userFollowId = body.substring(body.indexOf("/following/users/") + "/following/users/".length());
		userFollowId = userFollowId.substring(0, userFollowId.indexOf("/unfollow"));

		given()
			.redirects().follow(false)
			.when().post("/following/users/" + userFollowId + "/unfollow")
			.then()
			.statusCode(anyOf(is(302), is(303)));

		given()
			.when().get("/following")
			.then()
			.statusCode(200)
			.body(not(containsString(repo)));
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
	void dispatchPush(String actor, String summary)
	{
		ObjectNode node = mapper.createObjectNode();
		node.put("id", actor + "/activities/" + UUID.randomUUID());
		node.put("type", "Push");
		node.put("actor", actor);
		node.put("target", "refs/heads/main");
		node.put("summary", summary);
		dispatcher.dispatch(node);
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
