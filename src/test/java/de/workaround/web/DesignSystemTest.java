package de.workaround.web;

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

@QuarkusTest
class DesignSystemTest
{
	@Inject
	GitRepositoryService service;

	@Test
	void pagesLinkSharedStylesheetAndHaveNoInlineStyles()
	{
		given().when().get("/")
			.then().statusCode(200)
			.body(containsString("<link rel=\"stylesheet\" href=\"/shark.css\">"))
			.body(not(containsString("<style>")));
	}

	@Test
	void sharedStylesheetDefinesSharkTokens()
	{
		given().when().get("/shark.css")
			.then().statusCode(200)
			.body(containsString(":root"))
			.body(containsString("--accent"))
			.body(containsString("--ink"))
			.body(containsString("--canvas"));
	}

	@Test
	void landingPageShowsLogoAndBrandedHeader()
	{
		given().when().get("/")
			.then().statusCode(200)
			.body(containsString("landing-mark"))
			.body(containsString("git-shark"));
	}

	@Test
	void faviconIsServedAsSvgResource()
	{
		given().when().get("/favicon.svg")
			.then().statusCode(200)
			.header("Content-Type", containsString("image/svg+xml"));
	}

	@Test
	void explorePageShowsVisibilityBadges()
	{
		User owner = persistUser("ds-owner-" + unique());
		service.create(owner, "ds-badged", Repository.Visibility.PUBLIC, "demo");

		given().when().get("/explore")
			.then().statusCode(200)
			.body(containsString("class=\"badge"));
	}

	@Test
	void layoutContainsHotkeyHelpDialog()
	{
		given().when().get("/")
			.then().statusCode(200)
			.body(containsString("<dialog id=\"hotkey-help\""))
			.body(containsString("/shark-hotkeys.js"));
	}

	@Test
	void branchesPageMarksDefaultBranchWithBadge() throws Exception
	{
		User owner = persistUser("ds-badge-" + unique());
		Repository repo = service.create(owner, "ds-branched", Repository.Visibility.PUBLIC, null);
		de.workaround.git.GitTestSeeder.seed(service.repositoryPath(repo),
			java.util.Map.of("a.txt", "a\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));

		given().when().get("/repos/" + owner.username + "/ds-branched/branches")
			.then().statusCode(200)
			.body(containsString("badge badge-default"));
	}

	@Test
	void commitPaginationUsesSecondaryButtons() throws Exception
	{
		User owner = persistUser("ds-pager-" + unique());
		Repository repo = service.create(owner, "ds-paged", Repository.Visibility.PUBLIC, null);
		de.workaround.git.GitTestSeeder.seed(service.repositoryPath(repo),
			java.util.Map.of("a.txt", "a\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)), 3);

		String base = "/repos/" + owner.username + "/ds-paged/commits/main";

		given().when().get(base + "?size=2")
			.then().statusCode(200)
			.body(containsString("class=\"btn btn-secondary\""))
			.body(containsString("Older"));

		given().when().get(base + "?size=2&page=1")
			.then().statusCode(200)
			.body(containsString("class=\"btn btn-secondary\""))
			.body(containsString("Newer"));
	}

	@Test
	@io.quarkus.test.security.TestSecurity(user = "ds-settings")
	void settingsPagesUseDesignSystemActionColumns()
	{
		persistUser("ds-settings");

		given().when().get("/settings/keys")
			.then().statusCode(200)
			.body(containsString("class=\"btn btn-primary\""))
			.body(containsString("class=\"actions\""));

		given().when().get("/settings/tokens")
			.then().statusCode(200)
			.body(containsString("class=\"btn btn-primary\""))
			.body(containsString("class=\"actions\""));
	}

	@Test
	void copyButtonConfirmationStyleWorksOutsideCloneDialog()
	{
		// the copied-state color must apply to every [data-copy] button (e.g. the token page),
		// not only to buttons inside the clone dialog
		given().when().get("/shark.css")
			.then().statusCode(200)
			.body(containsString("\n.copy-btn.copied"));
	}

	@Test
	void fontsAreSelfHostedWithoutCdnRequests()
	{
		given().when().get("/")
			.then().statusCode(200)
			.body(not(containsString("fonts.googleapis.com")))
			.body(not(containsString("fonts.gstatic.com")));

		given().when().get("/shark.css")
			.then().statusCode(200)
			.body(containsString("@font-face"))
			.body(containsString("/fonts/"));

		given().when().get("/fonts/space-grotesk-latin.woff2")
			.then().statusCode(200);
		given().when().get("/fonts/jetbrains-mono-latin.woff2")
			.then().statusCode(200);
	}

	private static String unique()
	{
		return UUID.randomUUID().toString().substring(0, 8);
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

	@Inject
	User.Repo userRepo;
}
