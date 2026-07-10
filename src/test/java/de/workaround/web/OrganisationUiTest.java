package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.account.OrganisationService;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Organisation;
import de.workaround.model.OrganisationMember;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Organisation pages: creation with collision-checked names, the public org page with a
 * visibility-filtered repo list, owner-only member management, and the org owner selector in the
 * new-repository flow.
 */
@QuarkusTest
class OrganisationUiTest
{
	private static final String CREATOR = "orgui-creator";

	private static final String MEMBER = "orgui-member";

	private static final String GUEST = "orgui-guest";

	private static final String STRANGER = "orgui-stranger";

	@Inject
	OrganisationService organisations;

	@Inject
	GitRepositoryService service;

	@Inject
	User.Repo users;

	@Test
	@TestSecurity(user = CREATOR)
	void createsOrganisationAndSeesItsPage()
	{
		persistUser(CREATOR);
		String name = uniqueName();

		given()
			.redirects().follow(false)
			.formParam("name", name)
			.formParam("displayName", "ACME Inc.")
			.when().post("/orgs")
			.then().statusCode(303);

		given()
			.when().get("/orgs/" + name)
			.then().statusCode(200)
			.body(containsString(name))
			.body(containsString("ACME Inc."));
	}

	@Test
	@TestSecurity(user = CREATOR)
	void creatingOrganisationWithTakenUsernameShowsError()
	{
		persistUser(CREATOR);

		given()
			.redirects().follow(false)
			.formParam("name", CREATOR)
			.when().post("/orgs")
			.then().statusCode(400)
			.body(containsString("already taken"));
	}

	@Test
	void unknownOrganisationIs404()
	{
		given()
			.when().get("/orgs/no-such-org")
			.then().statusCode(404);
	}

	@Test
	@TestSecurity(user = STRANGER)
	void orgPageFiltersRepositoriesByVisibility()
	{
		persistUser(STRANGER);
		User creator = persistUser(CREATOR);
		Organisation org = createOrg(creator);
		createOrgRepo(org, "org-public", Repository.Visibility.PUBLIC);
		createOrgRepo(org, "org-secret", Repository.Visibility.PRIVATE);

		given()
			.when().get("/orgs/" + org.name)
			.then().statusCode(200)
			.body(containsString("org-public"))
			.body(not(containsString("org-secret")));
	}

	@Test
	@TestSecurity(user = GUEST)
	void guestSeesPrivateOrgRepositoryOnOrgPageAndInUi()
	{
		User creator = persistUser(CREATOR);
		User guest = persistUser(GUEST);
		Organisation org = createOrg(creator);
		addMember(creator, org, guest, OrganisationMember.Role.GUEST);
		Repository repo = createOrgRepo(org, "guest-visible", Repository.Visibility.PRIVATE);

		given()
			.when().get("/orgs/" + org.name)
			.then().statusCode(200)
			.body(containsString("guest-visible"));

		given()
			.when().get("/repos/" + org.name + "/" + repo.name)
			.then().statusCode(200);
	}

	@Test
	@TestSecurity(user = STRANGER)
	void strangerCannotOpenPrivateOrgRepository()
	{
		persistUser(STRANGER);
		User creator = persistUser(CREATOR);
		Organisation org = createOrg(creator);
		Repository repo = createOrgRepo(org, "org-hidden", Repository.Visibility.PRIVATE);

		given()
			.when().get("/repos/" + org.name + "/" + repo.name)
			.then().statusCode(404);
	}

	@Test
	@TestSecurity(user = CREATOR)
	void ownerManagesMembers()
	{
		User creator = persistUser(CREATOR);
		persistUser(MEMBER);
		Organisation org = createOrg(creator);

		given()
			.redirects().follow(false)
			.formParam("username", MEMBER)
			.formParam("role", "MEMBER")
			.when().post("/orgs/" + org.name + "/members")
			.then().statusCode(303);

		given()
			.when().get("/orgs/" + org.name + "/members")
			.then().statusCode(200)
			.body(containsString(MEMBER));

		given()
			.redirects().follow(false)
			.formParam("role", "OWNER")
			.when().post("/orgs/" + org.name + "/members/" + MEMBER + "/role")
			.then().statusCode(303);

		given()
			.redirects().follow(false)
			.when().post("/orgs/" + org.name + "/members/" + MEMBER + "/remove")
			.then().statusCode(303);

		assertEquals(1, memberCount(org));
	}

	@Test
	@TestSecurity(user = CREATOR)
	void addingUnknownMemberShowsError()
	{
		User creator = persistUser(CREATOR);
		Organisation org = createOrg(creator);

		given()
			.redirects().follow(false)
			.formParam("username", "nobody-here")
			.formParam("role", "MEMBER")
			.when().post("/orgs/" + org.name + "/members")
			.then().statusCode(400)
			.body(containsString("No user with that username exists."));
	}

	@Test
	@TestSecurity(user = CREATOR)
	void lastOwnerCannotBeRemovedViaUi()
	{
		User creator = persistUser(CREATOR);
		Organisation org = createOrg(creator);

		given()
			.redirects().follow(false)
			.when().post("/orgs/" + org.name + "/members/" + CREATOR + "/remove")
			.then().statusCode(400)
			.body(containsString("last owner"));
	}

	@Test
	@TestSecurity(user = MEMBER)
	void memberCannotOpenMembersPage()
	{
		User creator = persistUser(CREATOR);
		User member = persistUser(MEMBER);
		Organisation org = createOrg(creator);
		addMember(creator, org, member, OrganisationMember.Role.MEMBER);

		given()
			.when().get("/orgs/" + org.name + "/members")
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = CREATOR)
	void newRepositoryFormOffersOrgOwnerAndCreatesOrgRepository()
	{
		User creator = persistUser(CREATOR);
		Organisation org = createOrg(creator);

		given()
			.when().get("/repos/new")
			.then().statusCode(200)
			.body(containsString(org.name));

		String repoName = "via-form-" + UUID.randomUUID().toString().substring(0, 8);
		given()
			.redirects().follow(false)
			.formParam("name", repoName)
			.formParam("visibility", "PUBLIC")
			.formParam("owner", org.name)
			.when().post("/repos")
			.then().statusCode(303)
			.header("Location", containsString("/repos/" + org.name + "/" + repoName));

		assertTrue(findRepo(org, repoName));
	}

	@Test
	@TestSecurity(user = MEMBER)
	void nonOwnerCannotCreateOrgRepository()
	{
		User creator = persistUser(CREATOR);
		User member = persistUser(MEMBER);
		Organisation org = createOrg(creator);
		addMember(creator, org, member, OrganisationMember.Role.MEMBER);

		given()
			.redirects().follow(false)
			.formParam("name", "sneaky")
			.formParam("visibility", "PUBLIC")
			.formParam("owner", org.name)
			.when().post("/repos")
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = MEMBER)
	void dashboardListsOrganisationsTheUserBelongsTo()
	{
		User creator = persistUser(CREATOR);
		User member = persistUser(MEMBER);
		Organisation org = createOrg(creator);
		addMember(creator, org, member, OrganisationMember.Role.MEMBER);

		given()
			.when().get("/")
			.then().statusCode(200)
			.body(containsString("/orgs/" + org.name));
	}

	@Test
	@TestSecurity(user = MEMBER)
	void orgRepositoryAppearsOnMemberDashboard()
	{
		User creator = persistUser(CREATOR);
		User member = persistUser(MEMBER);
		Organisation org = createOrg(creator);
		addMember(creator, org, member, OrganisationMember.Role.MEMBER);
		Repository repo = createOrgRepo(org, "dash-visible", Repository.Visibility.PRIVATE);

		given()
			.when().get("/")
			.then().statusCode(200)
			.body(containsString(repo.name));
	}

	private static String uniqueName()
	{
		return "orgui-" + UUID.randomUUID().toString().substring(0, 8);
	}

	@Transactional
	Organisation createOrg(User creator)
	{
		return organisations.create(creator, uniqueName(), null);
	}

	Repository createOrgRepo(Organisation org, String prefix, Repository.Visibility visibility)
	{
		return service.create(org, prefix + "-" + UUID.randomUUID().toString().substring(0, 8), visibility, null);
	}

	@Transactional
	void addMember(User actor, Organisation org, User user, OrganisationMember.Role role)
	{
		organisations.addMember(actor, org, user.username, role);
	}

	@Transactional
	long memberCount(Organisation org)
	{
		return organisations.members(org).size();
	}

	@Transactional
	boolean findRepo(Organisation org, String name)
	{
		return service.find(org.name, name).isPresent();
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
