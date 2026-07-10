package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class RepositoryVisibilityTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	Repository.Repo repositories;

	@Inject
	User.Repo users;

	@Inject
	EntityManager em;

	@Test
	@TestSecurity(user = "vis-owner")
	void ownerChangesPrivateToPublic()
	{
		User owner = persistUser("vis-owner");
		Repository repo = service.create(owner, "opening-up", Repository.Visibility.PRIVATE, null);

		given().redirects().follow(false)
			.formParam("visibility", "PUBLIC")
			.when().post("/repos/vis-owner/opening-up/visibility")
			.then().statusCode(anyOf(is(302), is(303)));

		assertEquals(Repository.Visibility.PUBLIC, byId(repo.id).visibility);
	}

	@Test
	@TestSecurity(user = "vis-closer")
	void ownerChangesPublicToPrivate()
	{
		User owner = persistUser("vis-closer");
		Repository repo = service.create(owner, "going-dark", Repository.Visibility.PUBLIC, null);

		given().redirects().follow(false)
			.formParam("visibility", "PRIVATE")
			.when().post("/repos/vis-closer/going-dark/visibility")
			.then().statusCode(anyOf(is(302), is(303)));

		assertEquals(Repository.Visibility.PRIVATE, byId(repo.id).visibility);
	}

	@Test
	@TestSecurity(user = "vis-stranger")
	void nonOwnerCannotChangeVisibility()
	{
		persistUser("vis-stranger");
		User owner = persistUser("vis-real-owner-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "guarded", Repository.Visibility.PRIVATE, null);

		given().redirects().follow(false)
			.formParam("visibility", "PUBLIC")
			.when().post("/repos/" + owner.username + "/guarded/visibility")
			.then().statusCode(404);

		assertEquals(Repository.Visibility.PRIVATE, byId(repo.id).visibility);
	}

	@Test
	@TestSecurity(user = "vis-bogus")
	void rejectsUnknownVisibilityValue()
	{
		User owner = persistUser("vis-bogus");
		Repository repo = service.create(owner, "bogus", Repository.Visibility.PRIVATE, null);

		given().redirects().follow(false)
			.formParam("visibility", "FRIENDS_ONLY")
			.when().post("/repos/vis-bogus/bogus/visibility")
			.then().statusCode(400);

		assertEquals(Repository.Visibility.PRIVATE, byId(repo.id).visibility);
	}

	@Test
	@TestSecurity(user = "vis-settings")
	void settingsPageOffersVisibilityChange()
	{
		User owner = persistUser("vis-settings");
		service.create(owner, "knobs", Repository.Visibility.PRIVATE, null);

		given().when().get("/repos/vis-settings/knobs/settings")
			.then().statusCode(200)
			.body(containsString("/repos/vis-settings/knobs/visibility"))
			.body(containsString("PRIVATE"));
	}

	@Test
	@TestSecurity(user = "vis-svc")
	void serviceRejectsNonAdminActor()
	{
		User owner = persistUser("vis-svc-owner-" + UUID.randomUUID().toString().substring(0, 8));
		User stranger = persistUser("vis-svc-stranger-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "svc", Repository.Visibility.PRIVATE, null);

		assertThrows(ForbiddenOperationException.class,
			() -> service.changeVisibility(stranger, repo, Repository.Visibility.PUBLIC));
		assertEquals(Repository.Visibility.PRIVATE, byId(repo.id).visibility);
	}

	// Clear the persistence context first: each HTTP call commits in its own transaction, so a
	// re-read of the same repository within one test method must not return the stale L1-cached instance.
	private Repository byId(UUID id)
	{
		em.clear();
		return repositories.findById(id);
	}

	@Transactional
	User persistUser(String name)
	{
		User existing = users.findByOidcSubOptional(name).orElse(null);
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
