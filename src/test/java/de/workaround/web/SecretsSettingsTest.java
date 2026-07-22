package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionSecret;
import de.workaround.model.ActionVariable;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The repository Actions settings UI (issue #2, phase 2): owners manage CI secrets and variables.
 * Secret values are encrypted at rest and never rendered back; non-owners cannot see or change them.
 */
@QuarkusTest
class SecretsSettingsTest
{
	private static final String OWNER = "sec-owner";

	private static final String STRANGER = "sec-stranger";

	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo users;

	@Inject
	ActionSecret.Repo secrets;

	@Inject
	ActionVariable.Repo variables;

	@Inject
	EntityManager em;

	@Test
	@TestSecurity(user = OWNER)
	void ownerAddsSecretStoredEncryptedAndNeverShown()
	{
		Repository repo = repoOwnedBy(OWNER, "sec-a");

		given().redirects().follow(false)
			.formParam("name", "API_TOKEN").formParam("value", "s3cr3t")
			.when().post(base(repo) + "/secrets")
			.then().statusCode(303);

		given().when().get(base(repo))
			.then().statusCode(200)
			.body(containsString("API_TOKEN"))
			.body(not(containsString("s3cr3t")));

		String stored = (String) em.createNativeQuery(
			"select value_encrypted from action_secret where name = 'API_TOKEN'").getSingleResult();
		assertTrue(stored.startsWith("enc1:"), "secret is encrypted at rest, got: " + stored);
	}

	@Test
	@TestSecurity(user = OWNER)
	void ownerAddsAndDeletesVariable()
	{
		Repository repo = repoOwnedBy(OWNER, "sec-b");

		given().redirects().follow(false)
			.formParam("name", "DEPLOY_ENV").formParam("value", "production")
			.when().post(base(repo) + "/variables")
			.then().statusCode(303);

		given().when().get(base(repo)).then().statusCode(200)
			.body(containsString("DEPLOY_ENV")).body(containsString("production"));

		UUID id = variables.findByRepository(repo).get(0).id;
		given().redirects().follow(false)
			.when().post(base(repo) + "/variables/" + id + "/delete")
			.then().statusCode(303);

		assertTrue(variables.findByRepository(repo).isEmpty());
	}

	@Test
	@TestSecurity(user = OWNER)
	void duplicateSecretNameIsRejected()
	{
		Repository repo = repoOwnedBy(OWNER, "sec-c");
		given().redirects().follow(false).formParam("name", "DUP").formParam("value", "a")
			.when().post(base(repo) + "/secrets").then().statusCode(303);

		given().formParam("name", "DUP").formParam("value", "b")
			.when().post(base(repo) + "/secrets")
			.then().statusCode(400).body(containsString("already"));

		assertEquals(1, secrets.findByRepository(repo).size());
	}

	@Test
	@TestSecurity(user = STRANGER)
	void strangerCannotViewOrManage()
	{
		Repository repo = repoOwnedBy(OWNER, "sec-d");
		given().when().get(base(repo)).then().statusCode(404);
		given().redirects().follow(false).formParam("name", "X").formParam("value", "y")
			.when().post(base(repo) + "/secrets").then().statusCode(404);
		assertTrue(secrets.findByRepository(repo).isEmpty());
	}

	@Test
	void anonymousCannotView()
	{
		Repository repo = repoOwnedBy(OWNER, "sec-e");
		given().when().get(base(repo)).then().statusCode(404);
	}

	private static String base(Repository repo)
	{
		return "/repos/" + repo.ownerHandle() + "/" + repo.name + "/settings/actions";
	}

	private Repository repoOwnedBy(String ownerName, String repoName)
	{
		User owner = persistUser(ownerName);
		persistUser(STRANGER);
		return createRepo(owner, repoName);
	}

	@Transactional
	Repository createRepo(User owner, String repoName)
	{
		return repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);
	}

	@Transactional
	User persistUser(String name)
	{
		return users.findByOidcSubOptional(name).orElseGet(() ->
		{
			User user = new User();
			user.oidcSub = name;
			user.username = name;
			user.persist();
			return user;
		});
	}
}
