package de.workaround.web;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.CollaboratorService;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.RepositoryCollaborator;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Owner-only collaborator management under /repos/{owner}/{name}/settings/collaborators: the owner
 * adds and removes local users by username, errors surface on the page, and non-owners get 403
 * (404 when they cannot even see the repository).
 */
@QuarkusTest
class CollaboratorSettingsTest
{
	private static final String OWNER = "collab-owner";

	private static final String STRANGER = "collab-stranger";

	private static final String MEMBER = "collab-member";

	@Inject
	GitRepositoryService service;

	@Inject
	CollaboratorService collaboratorService;

	@Inject
	User.Repo users;

	@Test
	@TestSecurity(user = OWNER)
	void ownerSeesCollaboratorsPageAndAddsUser()
	{
		persistUser(MEMBER);
		Repository repo = repoOwnedBy(OWNER, "collab-add", Repository.Visibility.PUBLIC);

		given()
			.when().get(settingsPath(repo))
			.then().statusCode(200)
			.body(containsString("Collaborators"));

		given()
			.redirects().follow(false)
			.formParam("username", MEMBER)
			.when().post(settingsPath(repo))
			.then().statusCode(303);

		given()
			.when().get(settingsPath(repo))
			.then().statusCode(200)
			.body(containsString(MEMBER));

		assertEquals(1, listCollaborators(repo).size());
	}

	@Test
	@TestSecurity(user = OWNER)
	void ownerRemovesCollaborator()
	{
		User member = persistUser(MEMBER);
		Repository repo = repoOwnedBy(OWNER, "collab-remove", Repository.Visibility.PUBLIC);
		addCollaborator(repo, member);

		given()
			.redirects().follow(false)
			.when().post(settingsPath(repo) + "/" + MEMBER + "/remove")
			.then().statusCode(303);

		assertEquals(0, listCollaborators(repo).size());
		given()
			.when().get(settingsPath(repo))
			.then().statusCode(200)
			.body(not(containsString(MEMBER)));
	}

	@Test
	@TestSecurity(user = OWNER)
	void addingUnknownUsernameShowsError()
	{
		Repository repo = repoOwnedBy(OWNER, "collab-unknown", Repository.Visibility.PUBLIC);

		given()
			.redirects().follow(false)
			.formParam("username", "does-not-exist")
			.when().post(settingsPath(repo))
			.then().statusCode(400)
			.body(containsString("No user with that username exists."));
	}

	@Test
	@TestSecurity(user = OWNER)
	void addingExistingCollaboratorShowsError()
	{
		User member = persistUser(MEMBER);
		Repository repo = repoOwnedBy(OWNER, "collab-dup", Repository.Visibility.PUBLIC);
		addCollaborator(repo, member);

		given()
			.redirects().follow(false)
			.formParam("username", MEMBER)
			.when().post(settingsPath(repo))
			.then().statusCode(400)
			.body(containsString("That user is already a collaborator."));

		assertEquals(1, listCollaborators(repo).size());
	}

	@Test
	@TestSecurity(user = OWNER)
	void addingTheOwnerShowsError()
	{
		Repository repo = repoOwnedBy(OWNER, "collab-self", Repository.Visibility.PUBLIC);

		given()
			.redirects().follow(false)
			.formParam("username", OWNER)
			.when().post(settingsPath(repo))
			.then().statusCode(400)
			.body(containsString("The owner cannot be added as a collaborator."));

		assertEquals(0, listCollaborators(repo).size());
	}

	@Test
	@TestSecurity(user = STRANGER)
	void nonOwnerGets403OnPublicRepositorySettings()
	{
		persistUser(STRANGER);
		Repository repo = repoOwnedBy(OWNER, "collab-403", Repository.Visibility.PUBLIC);

		given()
			.when().get(settingsPath(repo))
			.then().statusCode(403);

		given()
			.redirects().follow(false)
			.formParam("username", STRANGER)
			.when().post(settingsPath(repo))
			.then().statusCode(403);

		assertEquals(0, listCollaborators(repo).size());
	}

	@Test
	@TestSecurity(user = MEMBER)
	void collaboratorCannotManageCollaborators()
	{
		User member = persistUser(MEMBER);
		Repository repo = repoOwnedBy(OWNER, "collab-member-403", Repository.Visibility.PUBLIC);
		addCollaborator(repo, member);

		given()
			.when().get(settingsPath(repo))
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = STRANGER)
	void privateRepositorySettingsAreHiddenFromStrangers()
	{
		persistUser(STRANGER);
		Repository repo = repoOwnedBy(OWNER, "collab-hidden", Repository.Visibility.PRIVATE);

		given()
			.when().get(settingsPath(repo))
			.then().statusCode(404);
	}

	@Test
	void anonymousGets403OnPublicRepositorySettings()
	{
		Repository repo = repoOwnedBy(OWNER, "collab-anon", Repository.Visibility.PUBLIC);

		given()
			.when().get(settingsPath(repo))
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = MEMBER)
	void collaboratorReadsPrivateRepositoryInUi()
	{
		User member = persistUser(MEMBER);
		Repository repo = repoOwnedBy(OWNER, "collab-private-read", Repository.Visibility.PRIVATE);
		addCollaborator(repo, member);

		given()
			.when().get("/repos/" + OWNER + "/" + repo.name)
			.then().statusCode(200);
	}

	@Test
	@TestSecurity(user = STRANGER)
	void strangerStillCannotReadPrivateRepositoryInUi()
	{
		persistUser(STRANGER);
		Repository repo = repoOwnedBy(OWNER, "collab-private-hidden", Repository.Visibility.PRIVATE);

		given()
			.when().get("/repos/" + OWNER + "/" + repo.name)
			.then().statusCode(404);
	}

	@Test
	@TestSecurity(user = OWNER)
	void ownerSeesSettingsLinkInSidebar()
	{
		Repository repo = repoOwnedBy(OWNER, "collab-nav", Repository.Visibility.PUBLIC);

		given()
			.when().get("/repos/" + OWNER + "/" + repo.name)
			.then().statusCode(200)
			.body(containsString("settings/collaborators"));
	}

	@Test
	@TestSecurity(user = STRANGER)
	void nonOwnerDoesNotSeeSettingsLinkInSidebar()
	{
		persistUser(STRANGER);
		Repository repo = repoOwnedBy(OWNER, "collab-nav-hidden", Repository.Visibility.PUBLIC);

		given()
			.when().get("/repos/" + OWNER + "/" + repo.name)
			.then().statusCode(200)
			.body(not(containsString("settings/collaborators")));
	}

	private static String settingsPath(Repository repo)
	{
		return "/repos/" + OWNER + "/" + repo.name + "/settings/collaborators";
	}

	private Repository repoOwnedBy(String username, String name, Repository.Visibility visibility)
	{
		User owner = persistUser(username);
		return service.create(owner, name + "-" + UUID.randomUUID().toString().substring(0, 8), visibility, null);
	}

	@Transactional
	void addCollaborator(Repository repo, User member)
	{
		collaboratorService.add(repo.owner, repo, member.username);
	}

	@Transactional
	List<RepositoryCollaborator> listCollaborators(Repository repo)
	{
		return collaboratorService.list(repo);
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
