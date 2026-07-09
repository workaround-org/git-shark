package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.IssueService;
import de.workaround.model.Issue;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

@QuarkusTest
class IssueUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	IssueService issueService;

	@Inject
	User.Repo userRepo;

	@Test
	@TestSecurity(user = "iss-owner")
	void ownerCanCreateBrowseTransitionAndDeleteAnIssue()
	{
		User owner = persistUser("iss-owner");
		Repository repo = service.create(owner, "board", Repository.Visibility.PUBLIC, null);
		String base = "/repos/" + owner.username + "/board/issues";

		// create (do not follow the redirect: we want to assert the 303 and read Location)
		String location = given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("title", "Broken pipeline").formParam("description", "CI fails on native")
			.when().post(base)
			.then().statusCode(303)
			.extract().header("Location");

		// the issue shows up in the list with its title and default status
		given().when().get(base)
			.then().statusCode(200)
			.body(containsString("Broken pipeline"))
			.body(containsString("Planned"));

		// detail page shows the description
		given().when().get(location)
			.then().statusCode(200)
			.body(containsString("CI fails on native"))
			.body(containsString("Planned"));

		// move to "In development"
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded").formParam("status", "IN_DEVELOPMENT")
			.when().post(location + "/status")
			.then().statusCode(303);
		given().when().get(location).then().statusCode(200).body(containsString("In development"));

		// delete
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.when().post(location + "/delete")
			.then().statusCode(303);
		given().when().get(base).then().statusCode(200).body(not(containsString("Broken pipeline")));
	}

	@Test
	@TestSecurity(user = "iss-md")
	void issueDescriptionRendersMarkdown()
	{
		User owner = persistUser("iss-md");
		Repository repo = service.create(owner, "mdesc", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Styled",
			"Some **bold** text\n\n<script>alert('xss')</script>");

		// markdown is rendered to HTML; embedded raw HTML stays escaped
		given().when().get("/repos/" + owner.username + "/mdesc/issues/" + issue.number)
			.then().statusCode(200)
			.body(containsString("<strong>bold</strong>"))
			.body(not(containsString("<script>alert('xss')</script>")));
	}

	@Test
	@TestSecurity(user = "iss-owner4")
	void createIssueIsADedicatedPageLinkedFromTheList()
	{
		User owner = persistUser("iss-owner4");
		service.create(owner, "np", Repository.Visibility.PUBLIC, null);
		String base = "/repos/" + owner.username + "/np/issues";

		// the list links to a dedicated new-issue page instead of embedding a create form
		given().when().get(base)
			.then().statusCode(200)
			.body(containsString(base + "/new"));

		// the new-issue page renders a form that posts back to the issues collection
		given().when().get(base + "/new")
			.then().statusCode(200)
			.body(containsString("<form"))
			.body(containsString("name=\"title\""))
			.body(containsString("action=\"" + base + "\""));
	}

	@Test
	@TestSecurity(user = "iss-owner5")
	void repoOverviewShowsOpenIssueCount()
	{
		User owner = persistUser("iss-owner5");
		Repository repo = service.create(owner, "counts", Repository.Visibility.PUBLIC, null);
		issueService.create(owner, repo, "planned a", null);
		issueService.create(owner, repo, "planned b", null);
		Issue done = issueService.create(owner, repo, "done c", null);
		issueService.updateStatus(owner, done, Issue.Status.DONE);

		// empty repo => branch/tag/commit counts are all 0, so "2" can only be the open-issue count
		given().when().get("/repos/" + owner.username + "/counts")
			.then().statusCode(200)
			.body(containsString("Issues"))
			.body(containsString("<span class=\"ct\">2</span>"));
	}

	@Test
	@TestSecurity(user = "iss-owner6")
	void doneIssuesGoIntoACollapsibleArchive()
	{
		User owner = persistUser("iss-owner6");
		Repository repo = service.create(owner, "arch", Repository.Visibility.PUBLIC, null);
		issueService.create(owner, repo, "Open task", null);
		Issue done = issueService.create(owner, repo, "Finished task", null);
		issueService.updateStatus(owner, done, Issue.Status.DONE);

		given().when().get("/repos/" + owner.username + "/arch/issues")
			.then().statusCode(200)
			.body(containsString("Open task"))
			.body(containsString("Finished task"))
			.body(containsString("#1"))
			.body(containsString("#2"))
			.body(containsString("<details class=\"archive\""))
			.body(containsString("Archive"));
	}

	@Test
	@TestSecurity(user = "iss-owner7")
	void noArchiveSectionWhenNothingIsDone()
	{
		User owner = persistUser("iss-owner7");
		Repository repo = service.create(owner, "noarch", Repository.Visibility.PUBLIC, null);
		issueService.create(owner, repo, "Just open", null);

		given().when().get("/repos/" + owner.username + "/noarch/issues")
			.then().statusCode(200)
			.body(containsString("Just open"))
			.body(not(containsString("class=\"archive\"")));
	}

	@Test
	@TestSecurity(user = "iss-side")
	void issuePagesShowTheRepositorySidebar()
	{
		User owner = persistUser("iss-side");
		Repository repo = service.create(owner, "sb", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Sidebar please", null);
		String base = "/repos/" + owner.username + "/sb";

		given().when().get(base + "/issues")
			.then().statusCode(200).body(containsString("class=\"repo-nav\""));
		given().when().get(base + "/issues/" + issue.number)
			.then().statusCode(200).body(containsString("class=\"repo-nav\""));
		given().when().get(base + "/issues/new")
			.then().statusCode(200).body(containsString("class=\"repo-nav\""));
	}

	@Test
	@TestSecurity(user = "iss-num")
	void issuePagesAreAddressedByPerRepoNumberAndOldUuidUrlsRedirect()
	{
		User owner = persistUser("iss-num");
		Repository repo = service.create(owner, "numbered", Repository.Visibility.PUBLIC, null);
		String base = "/repos/" + owner.username + "/numbered/issues";
		Issue issue = issueService.create(owner, repo, "First", null);

		// creating via the form redirects to the number URL, not the UUID
		String location = given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded").formParam("title", "Second")
			.when().post(base)
			.then().statusCode(303)
			.extract().header("Location");
		org.junit.jupiter.api.Assertions.assertTrue(location.endsWith(base + "/2"),
			"expected redirect to number URL, got: " + location);

		// the detail page is served under the number
		given().when().get(base + "/" + issue.number)
			.then().statusCode(200).body(containsString("First"));

		// the list links issues by number
		given().when().get(base)
			.then().statusCode(200).body(containsString(base + "/" + issue.number + "\""));

		// old UUID links redirect permanently to the number URL
		given().redirects().follow(false).when().get(base + "/" + issue.id)
			.then().statusCode(301)
			.header("Location", org.hamcrest.Matchers.endsWith(base + "/" + issue.number));

		// unknown numbers and malformed ids are 404
		given().when().get(base + "/999").then().statusCode(404);
		given().when().get(base + "/not-a-number").then().statusCode(404);
	}

	@Test
	@TestSecurity(user = "iss-edit")
	void ownerCanEditTitleAndDescription()
	{
		User owner = persistUser("iss-edit");
		Repository repo = service.create(owner, "editable", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Old title", "Old **body**");
		String base = "/repos/" + owner.username + "/editable/issues";
		String editUrl = base + "/" + issue.number + "/edit";

		// the detail page links to the edit form
		given().when().get(base + "/" + issue.number)
			.then().statusCode(200)
			.body(containsString(editUrl));

		// the edit form is pre-filled with the current title and description
		given().when().get(editUrl)
			.then().statusCode(200)
			.body(containsString("value=\"Old title\""))
			.body(containsString("Old **body**"))
			.body(containsString("action=\"" + editUrl + "\""));

		// saving redirects back to the detail page
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("title", "New title").formParam("description", "New **body**")
			.when().post(editUrl)
			.then().statusCode(303)
			.header("Location", org.hamcrest.Matchers.endsWith(base + "/" + issue.number));

		// the updated description is rendered as markdown
		given().when().get(base + "/" + issue.number)
			.then().statusCode(200)
			.body(containsString("New title"))
			.body(containsString("<strong>body</strong>"))
			.body(not(containsString("Old title")));
	}

	@Test
	void anonymousCannotEditIssues()
	{
		User owner = persistUser("iss-edit2-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "noedit", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Locked", null);
		String editUrl = "/repos/" + owner.username + "/noedit/issues/" + issue.number + "/edit";

		given().when().get(editUrl).then().statusCode(403);
		given().contentType("application/x-www-form-urlencoded")
			.formParam("title", "Hijacked")
			.when().post(editUrl)
			.then().statusCode(403);

		// visitors don't see the edit link on the detail page
		given().when().get("/repos/" + owner.username + "/noedit/issues/" + issue.number)
			.then().statusCode(200)
			.body(not(containsString("/edit")));
	}

	@Test
	void anonymousCannotCreateIssues()
	{
		User owner = persistUser("iss-owner2-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "board", Repository.Visibility.PUBLIC, null);

		given().contentType("application/x-www-form-urlencoded").formParam("title", "Sneaky")
			.when().post("/repos/" + owner.username + "/board/issues")
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = "iss-stranger")
	void privateRepositoryIssuesAreHiddenFromStrangers()
	{
		persistUser("iss-stranger");
		User owner = persistUser("iss-owner3-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "secret", Repository.Visibility.PRIVATE, null);

		given().when().get("/repos/" + owner.username + "/secret/issues")
			.then().statusCode(404);
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

}
