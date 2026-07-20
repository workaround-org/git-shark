package de.workaround.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
import de.workaround.git.MergeRequestService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.not;

@QuarkusTest
class MergeRequestUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	MergeRequestService mergeRequestService;

	@Inject
	User.Repo userRepo;

	@Test
	@TestSecurity(user = "mru-owner")
	void ownerCanOpenViewDiffAndMergeAMergeRequest()
	{
		User owner = persistUser("mru-owner");
		Repository repo = seed(owner, "board");
		String base = "/repos/" + owner.username + "/board/merge-requests";

		// the new-MR form lets the owner pick source and target branches
		given().when().get(base + "/new")
			.then().statusCode(200)
			.body(containsString("<form"))
			.body(containsString("name=\"sourceBranch\""))
			.body(containsString("name=\"targetBranch\""))
			.body(containsString("feature"));

		// create it
		String location = given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("title", "Ship the feature").formParam("description", "adds feature.txt")
			.formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base)
			.then().statusCode(303)
			.extract().header("Location");

		// list shows it as open
		given().when().get(base)
			.then().statusCode(200)
			.body(containsString("Ship the feature"))
			.body(containsString("Open"));

		// detail shows the diff of the source branch and a merge control
		given().when().get(location)
			.then().statusCode(200)
			.body(containsString("feature.txt"))
			.body(containsString("feature"))
			.body(containsString("Merge"));

		// merge it
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.when().post(location + "/merge")
			.then().statusCode(303);

		given().when().get(location).then().statusCode(200).body(containsString("Merged"));
	}

	@Test
	@TestSecurity(user = "mru-closer")
	void ownerCanCloseAMergeRequestWithoutMerging()
	{
		User owner = persistUser("mru-closer");
		Repository repo = seed(owner, "closeboard");
		String base = "/repos/" + owner.username + "/closeboard/merge-requests";

		String location = given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("title", "Never mind").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base)
			.then().statusCode(303)
			.extract().header("Location");

		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.when().post(location + "/close")
			.then().statusCode(303);

		given().when().get(location).then().statusCode(200).body(containsString("Closed"));
	}

	@Test
	@TestSecurity(user = "mru-assign")
	void assigneeAndReviewerCanBeSetAndClearedViaTheForm()
	{
		User owner = persistUser("mru-assign");
		User helper = persistUser("mru-helper");
		seed(owner, "assignboard");
		String base = "/repos/" + owner.username + "/assignboard/merge-requests";
		String location = given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("title", "Assign me").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base).then().statusCode(303).extract().header("Location");

		// the detail page offers assignee and reviewer pickers
		given().when().get(location)
			.then().statusCode(200)
			.body(containsString("class=\"assignee-pop\""))
			.body(containsString("Reviewer"));

		// assign a person via the form
		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("assignee", helper.username)
			.when().post(location + "/assign").then().statusCode(303);
		given().when().get(location).then().statusCode(200)
			.body(containsString("class=\"assignee-name\"")).body(containsString(helper.username));

		// set a reviewer via the form
		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("reviewer", helper.username)
			.when().post(location + "/reviewer").then().statusCode(303);

		// clearing the assignee (blank) removes it again
		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("assignee", "")
			.when().post(location + "/assign").then().statusCode(303);
		given().when().get(location).then().statusCode(200).body(containsString("No one assigned"));
	}

	@Test
	@TestSecurity(user = "mru-contrib")
	void pickerSuggestsTopContributorsOfTheRepo()
	{
		User owner = persistUser("mru-contrib");
		// a platform account whose email matches the commit author seeded on the default branch,
		// but who is neither owner nor collaborator
		User contributor = persistUserWithEmail("mru-topdev", "seed@example.com");
		seed(owner, "contribboard");
		String base = "/repos/" + owner.username + "/contribboard/merge-requests";
		String location = given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("title", "Suggest me").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base).then().statusCode(303).extract().header("Location");

		// the picker suggests the top contributor even though they are not a collaborator
		given().when().get(location).then().statusCode(200)
			.body(containsString("value=\"" + contributor.username + "\""))
			.body(containsString("name=\"reviewer\" value=\"" + contributor.username + "\""));
	}

	@Test
	@TestSecurity(user = "mru-badassign")
	void assigningAnUnknownUsernameIsRejected()
	{
		User owner = persistUser("mru-badassign");
		seed(owner, "badassignboard");
		String base = "/repos/" + owner.username + "/badassignboard/merge-requests";
		String location = given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("title", "Bad assign").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base).then().statusCode(303).extract().header("Location");

		given().contentType("application/x-www-form-urlencoded").formParam("assignee", "ghost-user")
			.when().post(location + "/assign").then().statusCode(400);
	}

	@Test
	void anonymousCannotAssignMergeRequests()
	{
		User owner = persistUser("mru-assign-anon-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = seed(owner, "noassignboard");
		// create the MR as the owner (via the service) so an anonymous assign attempt has a target
		var mr = mergeRequestService.create(owner, repo, "x", null, "feature", "main");

		given().contentType("application/x-www-form-urlencoded").formParam("assignee", owner.username)
			.when().post("/repos/" + owner.username + "/noassignboard/merge-requests/" + mr.number + "/assign")
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = "mru-numbers")
	void mergeRequestsUseSequentialNumbersInUrls()
	{
		User owner = persistUser("mru-numbers");
		seed(owner, "numboard");
		String base = "/repos/" + owner.username + "/numboard/merge-requests";

		// the first MR gets #1; the redirect URL is the number, not a UUID
		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("title", "First MR").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base).then().statusCode(303)
			.header("Location", endsWith("/merge-requests/1"));

		// reachable by number
		given().when().get(base + "/1").then().statusCode(200).body(containsString("First MR"));

		// the list links to the number URL
		given().when().get(base).then().statusCode(200).body(containsString("/merge-requests/1\""));
	}

	@Test
	@TestSecurity(user = "mru-legacy")
	void legacyUuidUrlRedirectsToNumber()
	{
		User owner = persistUser("mru-legacy");
		Repository repo = seed(owner, "legboard");
		var mr = mergeRequestService.create(owner, repo, "Legacy link", null, "feature", "main");
		String base = "/repos/" + owner.username + "/legboard/merge-requests";

		given().redirects().follow(false).when().get(base + "/" + mr.id)
			.then().statusCode(301)
			.header("Location", containsString("/merge-requests/" + mr.number));
	}

	@Test
	void anonymousCannotCreateMergeRequests()
	{
		User owner = persistUser("mru-anon-" + UUID.randomUUID().toString().substring(0, 8));
		seed(owner, "board");

		given().contentType("application/x-www-form-urlencoded")
			.formParam("title", "Sneaky").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post("/repos/" + owner.username + "/board/merge-requests")
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = "mru-stranger")
	void privateRepositoryMergeRequestsAreHiddenFromStrangers()
	{
		persistUser("mru-stranger");
		User owner = persistUser("mru-owner2-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "secret", Repository.Visibility.PRIVATE, null);

		given().when().get("/repos/" + owner.username + "/secret/merge-requests")
			.then().statusCode(404);
	}

	@Test
	@TestSecurity(user = "mru-side")
	void mergeRequestPagesShowTheRepositorySidebar()
	{
		User owner = persistUser("mru-side");
		seed(owner, "sb");
		String base = "/repos/" + owner.username + "/sb/merge-requests";

		String location = given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("title", "Sidebar MR").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base).then().statusCode(303).extract().header("Location");

		given().when().get(base).then().statusCode(200).body(containsString("class=\"repo-nav\""));
		given().when().get(base + "/new").then().statusCode(200).body(containsString("class=\"repo-nav\""));
		given().when().get(location).then().statusCode(200).body(containsString("class=\"repo-nav\""));
	}

	@Test
	@TestSecurity(user = "mru-count")
	void repoOverviewShowsOpenMergeRequestCount()
	{
		User owner = persistUser("mru-count");
		Repository repo = seed(owner, "counts");
		String base = "/repos/" + owner.username + "/counts/merge-requests";
		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("title", "one").formParam("sourceBranch", "feature").formParam("targetBranch", "main")
			.when().post(base).then().statusCode(303);

		given().when().get("/repos/" + owner.username + "/counts")
			.then().statusCode(200)
			.body(containsString("Merge requests"))
			.body(containsString("<span class=\"ct\">1</span>"));
	}

	/** Repo with a base commit on main and a divergent 'feature' branch adding feature.txt. */
	private Repository seed(User owner, String name)
	{
		try
		{
			Repository repo = service.create(owner, name, Repository.Visibility.PUBLIC, null);
			Path bare = service.repositoryPath(repo);
			GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)));
			Path work = Files.createTempDirectory("mruseed");
			try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(work.toFile()).call())
			{
				git.checkout().setCreateBranch(true).setName("feature").call();
				Files.writeString(work.resolve("feature.txt"), "feature\n");
				git.add().addFilepattern(".").call();
				git.commit().setMessage("feature work").setSign(false)
					.setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();
				git.push().setRefSpecs(new RefSpec("feature:refs/heads/feature")).call();
			}
			return repo;
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
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

	@Transactional
	User persistUserWithEmail(String name, String email)
	{
		User user = persistUser(name);
		user.email = email;
		user.persist();
		return user;
	}
}
