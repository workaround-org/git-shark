package de.workaround.federation;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

/**
 * Section 2: actor documents, content negotiation, and WebFinger. The test profile enables
 * federation with base-url https://shark.test.
 */
@QuarkusTest
class FederationActorsTest
{
	private static final String ACTIVITY_JSON = "application/activity+json";

	@Inject
	GitRepositoryService service;

	@Inject
	User.Repo userRepo;

	@Test
	void repositoryActorDocumentHasForgeFedShape()
	{
		User owner = persistUser("fed-act-bob-" + unique());
		service.create(owner, "lib", Repository.Visibility.PUBLIC, "a public library");

		given().accept(ACTIVITY_JSON)
			.when().get("/ap/repos/" + owner.username + "/lib")
			.then().statusCode(200)
			.header("Content-Type", containsString("activity+json"))
			.body(containsString("\"type\":\"Repository\""))
			.body(containsString("https://www.w3.org/ns/activitystreams"))
			.body(containsString("https://w3id.org/security/v1"))
			.body(containsString("https://forgefed.org/ns"))
			.body(containsString("/ap/repos/" + owner.username + "/lib/inbox"))
			.body(containsString("/ap/repos/" + owner.username + "/lib/outbox"))
			.body(containsString("/ap/repos/" + owner.username + "/lib/followers"))
			.body(containsString("#main-key"))
			.body(containsString("BEGIN PUBLIC KEY"));
	}

	@Test
	void personActorResolves()
	{
		User user = persistUser("fed-act-carol-" + unique());

		given().accept(ACTIVITY_JSON)
			.when().get("/ap/users/" + user.username)
			.then().statusCode(200)
			.body(containsString("\"type\":\"Person\""))
			.body(containsString("/ap/users/" + user.username + "/inbox"))
			.body(containsString("BEGIN PUBLIC KEY"));
	}

	@Test
	void personActorAdvertisesRepositoriesCollection()
	{
		User user = persistUser("fed-act-ivy-" + unique());

		given().accept(ACTIVITY_JSON)
			.when().get("/ap/users/" + user.username)
			.then().statusCode(200)
			.body(containsString("\"type\":\"Person\""))
			.body(containsString("/ap/users/" + user.username + "/repositories"));
	}

	@Test
	void personRepositoriesCollectionListsOnlyPublicRepos()
	{
		User owner = persistUser("fed-act-jane-" + unique());
		service.create(owner, "alpha", Repository.Visibility.PUBLIC, "first");
		service.create(owner, "beta", Repository.Visibility.PUBLIC, "second");
		service.create(owner, "hidden", Repository.Visibility.PRIVATE, "secret");

		given().accept(ACTIVITY_JSON)
			.when().get("/ap/users/" + owner.username + "/repositories")
			.then().statusCode(200)
			.body(containsString("\"type\":\"OrderedCollection\""))
			.body(containsString("\"totalItems\":2"))
			.body(containsString("/ap/repos/" + owner.username + "/alpha"))
			.body(containsString("/ap/repos/" + owner.username + "/beta"))
			.body(not(containsString("/ap/repos/" + owner.username + "/hidden")));
	}

	@Test
	void personRepositoriesCollectionEmptyForUnknownUserIsNotFound()
	{
		given().accept(ACTIVITY_JSON)
			.when().get("/ap/users/nobody-" + unique() + "/repositories")
			.then().statusCode(404);
	}

	@Test
	void instanceActorResolves()
	{
		given().accept(ACTIVITY_JSON)
			.when().get("/ap/instance")
			.then().statusCode(200)
			.body(containsString("\"type\":\"Application\""))
			.body(containsString("/ap/instance/inbox"))
			.body(containsString("BEGIN PUBLIC KEY"));
	}

	@Test
	void privateRepositoryHasNoActorDocument()
	{
		User owner = persistUser("fed-act-dave-" + unique());
		service.create(owner, "secret", Repository.Visibility.PRIVATE, null);

		given().accept(ACTIVITY_JSON)
			.when().get("/ap/repos/" + owner.username + "/secret")
			.then().statusCode(404);
	}

	@Test
	void emptyFollowersAndOutboxCollections()
	{
		User owner = persistUser("fed-act-erin-" + unique());
		service.create(owner, "coll", Repository.Visibility.PUBLIC, null);

		given().accept(ACTIVITY_JSON)
			.when().get("/ap/repos/" + owner.username + "/coll/followers")
			.then().statusCode(200)
			.body(containsString("\"type\":\"OrderedCollection\""))
			.body(containsString("\"totalItems\":0"));

		given().accept(ACTIVITY_JSON)
			.when().get("/ap/repos/" + owner.username + "/coll/outbox")
			.then().statusCode(200)
			.body(containsString("\"type\":\"OrderedCollection\""));
	}

	@Test
	void webfingerResolvesRepositoryActor()
	{
		User owner = persistUser("fed-wf-frank-" + unique());
		service.create(owner, "proj", Repository.Visibility.PUBLIC, null);

		given()
			.when().get("/.well-known/webfinger?resource=acct:" + owner.username + "/proj@shark.test")
			.then().statusCode(200)
			.body(containsString("\"rel\":\"self\""))
			.body(containsString("application/activity+json"))
			.body(containsString("/ap/repos/" + owner.username + "/proj"));
	}

	@Test
	void webfingerRejectsUnknownAndWrongHost()
	{
		given()
			.when().get("/.well-known/webfinger?resource=acct:nobody-" + unique() + "@shark.test")
			.then().statusCode(404);

		User owner = persistUser("fed-wf-gina-" + unique());
		service.create(owner, "elsewhere", Repository.Visibility.PUBLIC, null);

		given()
			.when().get("/.well-known/webfinger?resource=acct:" + owner.username + "/elsewhere@other.host")
			.then().statusCode(404);
	}

	@Test
	void contentNegotiationRedirectsToActorAndKeepsHtmlDefault()
	{
		User owner = persistUser("fed-neg-hank-" + unique());
		service.create(owner, "nego", Repository.Visibility.PUBLIC, null);
		String path = "/repos/" + owner.username + "/nego";

		given().accept(ACTIVITY_JSON).redirects().follow(false)
			.when().get(path)
			.then().statusCode(303)
			.header("Location", containsString("/ap/repos/" + owner.username + "/nego"));

		given().accept("text/html")
			.when().get(path)
			.then().statusCode(200)
			.body(containsString("git push"))
			.body(not(containsString("\"type\":\"Repository\"")));
	}

	private static String unique()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

	@Transactional
	User persistUser(String name)
	{
		return userRepo.findByOidcSubOptional(name).orElseGet(() -> {
			User user = new User();
			user.oidcSub = name;
			user.username = name;
			user.persist();
			return user;
		});
	}

}
