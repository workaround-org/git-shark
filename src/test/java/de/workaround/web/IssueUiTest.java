package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.CollaboratorService;
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
	CollaboratorService collaboratorService;

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
	@TestSecurity(user = "iss-assign")
	void assigneeInputSuggestsOwnerAndCollaborators()
	{
		User owner = persistUser("iss-assign");
		User collab = persistUser("iss-collab");
		Repository repo = service.create(owner, "assignable", Repository.Visibility.PUBLIC, null);
		collaboratorService.add(owner, repo, collab.username);
		Issue issue = issueService.create(owner, repo, "Assign me", null);

		// the assignee is a floating popover (click the row to open) with a click-to-assign people list
		// of the owner + collaborators, plus a free-text field backed by a datalist
		given().when().get("/repos/" + owner.username + "/assignable/issues/" + issue.number)
			.then().statusCode(200)
			.body(containsString("<details class=\"assignee-pop\""))
			.body(containsString("class=\"assignee-option"))
			.body(containsString("<datalist id=\"assignees\""))
			.body(containsString("list=\"assignees\""))
			.body(containsString("value=\"iss-assign\""))
			.body(containsString("value=\"iss-collab\""));
	}

	@Test
	@TestSecurity(user = "iss-assignee-av")
	void issueDetailShowsTheAssigneeWithAvatar()
	{
		User owner = persistUser("iss-assignee-av");
		Repository repo = service.create(owner, "avassign", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Has an owner", null);
		issueService.assign(owner, issue, owner.username);

		// the assignee is named in the metadata sidebar and rendered through the avatar tag
		// (initials fallback here, since the test user has no uploaded image)
		given().when().get("/repos/" + owner.username + "/avassign/issues/" + issue.number)
			.then().statusCode(200)
			.body(containsString("class=\"assignee-name\""))
			.body(containsString("class=\"av-fallback\""));
	}

	@Test
	@TestSecurity(user = "iss-assign-http")
	void assigningViaTheFormSetsAndClearsTheAssignee()
	{
		User owner = persistUser("iss-assign-http");
		User helper = persistUser("iss-assign-helper");
		Repository repo = service.create(owner, "httpassign", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Assign via form", null);
		String url = "/repos/" + owner.username + "/httpassign/issues/" + issue.number;

		// happy path: posting a valid username assigns and redirects back to the issue
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded").formParam("assignee", helper.username)
			.when().post(url + "/assign")
			.then().statusCode(303)
			.header("Location", org.hamcrest.Matchers.endsWith(url));
		given().when().get(url)
			.then().statusCode(200)
			.body(containsString("class=\"assignee-name\""))
			.body(containsString(helper.username));

		// posting a blank username clears the assignment again
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded").formParam("assignee", "")
			.when().post(url + "/assign")
			.then().statusCode(303);
		given().when().get(url).then().statusCode(200).body(containsString("No one assigned"));
	}

	@Test
	@TestSecurity(user = "iss-assign-bad")
	void assigningAnUnknownUsernameIsRejected()
	{
		User owner = persistUser("iss-assign-bad");
		Repository repo = service.create(owner, "badassign", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Bad assign", null);
		String url = "/repos/" + owner.username + "/badassign/issues/" + issue.number + "/assign";

		given().contentType("application/x-www-form-urlencoded").formParam("assignee", "ghost-user")
			.when().post(url)
			.then().statusCode(400);
	}

	@Test
	void anonymousCannotAssignIssues()
	{
		User owner = persistUser("iss-assign-anon-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "noassign", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Locked assign", null);
		String url = "/repos/" + owner.username + "/noassign/issues/" + issue.number + "/assign";

		given().contentType("application/x-www-form-urlencoded").formParam("assignee", owner.username)
			.when().post(url)
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = "iss-list-av")
	void issueListShowsTheAssigneeAvatarOnEachRow()
	{
		User owner = persistUser("iss-list-av");
		Repository repo = service.create(owner, "listav", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Assigned one", null);
		issueService.assign(owner, issue, owner.username);

		// assigned issues carry the assignee avatar on the right of their list row
		given().when().get("/repos/" + owner.username + "/listav/issues")
			.then().statusCode(200)
			.body(containsString("class=\"frow-assignee\""))
			.body(containsString("class=\"av-fallback\""));
	}

	@Test
	@TestSecurity(user = "iss-assets")
	void stylesheetLinkIsCacheBusted()
	{
		// every page shares layout.html; the shark.css link must carry a ?v= token so browsers pick up
		// CSS changes after a deploy instead of serving a stale cached copy
		User owner = persistUser("iss-assets");
		service.create(owner, "assetsrepo", Repository.Visibility.PUBLIC, null);

		given().when().get("/repos/" + owner.username + "/assetsrepo/issues")
			.then().statusCode(200)
			.body(containsString("/shark.css?v="));
	}

	@Test
	@TestSecurity(user = "iss-md")
	void issueDescriptionRendersMarkdown()
	{
		User owner = persistUser("iss-md");
		Repository repo = service.create(owner, "mdesc", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Styled",
			"Some **bold** text\n\n<script>alert('xss')</script>");

		// markdown is rendered to HTML inside a surface card; embedded raw HTML stays escaped
		given().when().get("/repos/" + owner.username + "/mdesc/issues/" + issue.number)
			.then().statusCode(200)
			.body(containsString("class=\"panel issue-desc\""))
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

		// the detail page links to the edit form from the header action cluster
		given().when().get(base + "/" + issue.number)
			.then().statusCode(200)
			.body(containsString(editUrl))
			.body(containsString("class=\"issue-header-actions\""));

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
	@TestSecurity(user = "iss-long")
	void longDescriptionsSurviveCreateAndEdit()
	{
		User owner = persistUser("iss-long");
		Repository repo = service.create(owner, "longdesc", Repository.Visibility.PUBLIC, null);
		String base = "/repos/" + owner.username + "/longdesc/issues";
		// well past the 2 KB Vert.x default form-attribute limit that used to answer 413
		String longBody = "A meaningful line of markdown text.\n".repeat(300);

		String location = given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("title", "Long story").formParam("description", longBody)
			.when().post(base)
			.then().statusCode(303)
			.extract().header("Location");

		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("title", "Long story").formParam("description", longBody + "\nEdited.")
			.when().post(location + "/edit")
			.then().statusCode(303);

		given().when().get(location)
			.then().statusCode(200)
			.body(containsString("Edited."));
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
