package de.workaround.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
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
class WebUiTest
{
	@Inject
	GitRepositoryService service;

	@Test
	void anonymousSeesOnlyPublicRepositoriesOnExplore() throws Exception
	{
		User owner = persistUser("ui-bob-" + unique());
		service.create(owner, "ui-pub", Repository.Visibility.PUBLIC, "public demo");
		service.create(owner, "ui-priv", Repository.Visibility.PRIVATE, null);

		given()
			.when().get("/explore")
			.then()
			.statusCode(200)
			.body(containsString("ui-pub"))
			.body(not(containsString("ui-priv")));
	}

	@Test
	void anonymousSeesLandingPageOnHome() throws Exception
	{
		User owner = persistUser("ui-zoe-" + unique());
		service.create(owner, "landing-pub", Repository.Visibility.PUBLIC, null);

		given()
			.when().get("/")
			.then()
			.statusCode(200)
			.body(containsString("Use AI as a tool, not as a feature"))
			.body(containsString("Focus on co-working, not lazy feeds"))
			.body(containsString("Easy and painless to self-host"))
			.body(not(containsString("landing-pub")));
	}

	@Test
	void landingPageHasAnimatedAsciiBackground() throws Exception
	{
		// background symbol layer is present in the markup...
		given()
			.when().get("/")
			.then()
			.statusCode(200)
			.body(containsString("class=\"ascii-bg\""));

		// ...and the animation is defined in the shared stylesheet (no inline <style> on /)
		given()
			.when().get("/shark.css")
			.then()
			.statusCode(200)
			.body(containsString("@keyframes ascii-glow"));
	}

	@Test
	@TestSecurity(user = "ui-alice")
	void ownerSeesOwnPrivateRepositoryOnHome() throws Exception
	{
		User alice = persistUser("ui-alice");
		service.create(alice, "alice-secret-" + unique(), Repository.Visibility.PRIVATE, null);

		String repoName = service.listVisibleTo(alice).stream()
			.filter(r -> r.name.startsWith("alice-secret-"))
			.findFirst().orElseThrow().name;

		given()
			.when().get("/")
			.then()
			.statusCode(200)
			.body(containsString(repoName));
	}

	@Test
	void fileBrowserListsDirectoriesAndEscapesTextContent() throws Exception
	{
		User owner = persistUser("ui-carol-" + unique());
		Repository repo = service.create(owner, "browse", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo), Map.of(
			"README.md", "<b>bold</b> readme\n".getBytes(StandardCharsets.UTF_8),
			"docs/guide.txt", "guide\n".getBytes(StandardCharsets.UTF_8),
			"logo.bin", new byte[] { 0, 1, 2, 3, 0, -1 }));

		String base = "/repos/" + owner.username + "/browse";

		given().when().get(base + "/tree/main")
			.then().statusCode(200)
			.body(containsString("README.md"))
			.body(containsString("docs"));

		given().when().get(base + "/tree/main/README.md")
			.then().statusCode(200)
			.body(containsString("&lt;b&gt;bold&lt;/b&gt;"))
			.body(not(containsString("<b>bold</b>")));

		given().when().get(base + "/tree/main/logo.bin")
			.then().statusCode(200)
			.body(containsString("/raw/main/logo.bin"));

		byte[] raw = given().when().get(base + "/raw/main/logo.bin")
			.then().statusCode(200)
			.extract().body().asByteArray();
		org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] { 0, 1, 2, 3, 0, -1 }, raw);
	}

	@Test
	void blobViewHighlightsSourceCodeByLanguage() throws Exception
	{
		User owner = persistUser("ui-hank-" + unique());
		Repository repo = service.create(owner, "syntax", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo), Map.of(
			"Main.java", "class Main {}\n".getBytes(StandardCharsets.UTF_8),
			"App.scala", "object App\n".getBytes(StandardCharsets.UTF_8),
			"Dockerfile", "FROM scratch\n".getBytes(StandardCharsets.UTF_8),
			"config.properties", "a=b\n".getBytes(StandardCharsets.UTF_8),
			"notes.unknownext", "just plain text\n".getBytes(StandardCharsets.UTF_8)));

		String base = "/repos/" + owner.username + "/syntax";

		given().when().get(base + "/tree/main/Main.java")
			.then().statusCode(200)
			.body(containsString("language-java"))
			.body(containsString("/highlight.min.js"));

		// languages served from the extra grammar bundle
		given().when().get(base + "/tree/main/App.scala")
			.then().statusCode(200)
			.body(containsString("language-scala"))
			.body(containsString("/highlight-extra.min.js"));

		given().when().get(base + "/tree/main/Dockerfile")
			.then().statusCode(200)
			.body(containsString("language-dockerfile"));

		// .properties reuses the ini grammar
		given().when().get(base + "/tree/main/config.properties")
			.then().statusCode(200)
			.body(containsString("language-ini"));

		// unknown extensions render as plain text, no highlighter loaded
		given().when().get(base + "/tree/main/notes.unknownext")
			.then().statusCode(200)
			.body(containsString("just plain text"))
			.body(not(containsString("/highlight.min.js")));
	}

	@Test
	void everyMappedHighlightLanguageHasABundledGrammar() throws Exception
	{
		String bundle = readResource("META-INF/resources/highlight.min.js") + "\n"
			+ readResource("META-INF/resources/highlight-extra.min.js");

		for (String language : RepositoryResource.highlightLanguages())
		{
			boolean registered = bundle.contains("grmr_" + language)
				|| bundle.contains("registerLanguage(\"" + language + "\"");
			org.junit.jupiter.api.Assertions.assertTrue(registered,
				"no highlight.js grammar bundled for mapped language: " + language);
		}
	}

	@Test
	void fileAndTreeViewsShowClickableBreadcrumbPath() throws Exception
	{
		User owner = persistUser("ui-jane-" + unique());
		Repository repo = service.create(owner, "crumbs", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("src/main/java/App.java", "class App {}\n".getBytes(StandardCharsets.UTF_8)));

		String base = "/repos/" + owner.username + "/crumbs";

		// directory view: the ref and each ancestor segment link to their tree; the current dir is not a link
		given().when().get(base + "/tree/main/src/main")
			.then().statusCode(200)
			.body(containsString("class=\"breadcrumb\""))
			.body(containsString("href=\"" + base + "/tree/main\""))
			.body(containsString("href=\"" + base + "/tree/main/src\""));

		// file view: clicking the ref jumps to the repo root, clicking a segment jumps into that directory
		given().when().get(base + "/tree/main/src/main/java/App.java")
			.then().statusCode(200)
			.body(containsString("class=\"breadcrumb\""))
			.body(containsString("href=\"" + base + "/tree/main\""))
			.body(containsString("href=\"" + base + "/tree/main/src/main/java\""));
	}

	@Test
	void directoryEntriesAreFullyClickableRows() throws Exception
	{
		User owner = persistUser("ui-karl-" + unique());
		Repository repo = service.create(owner, "rows", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo), Map.of(
			"docs/guide.md", "x\n".getBytes(StandardCharsets.UTF_8),
			"README.md", "x\n".getBytes(StandardCharsets.UTF_8)));

		String base = "/repos/" + owner.username + "/rows";

		// the whole row (icon + name) is a single anchor, so the entire box is clickable — not just the text
		given().when().get(base + "/tree/main")
			.then().statusCode(200)
			.body(containsString("<a class=\"frow\" href=\"" + base + "/tree/main/docs\""))
			.body(containsString("<a class=\"frow\" href=\"" + base + "/tree/main/README.md\""));
	}

	private static String readResource(String path) throws Exception
	{
		try (java.io.InputStream in = WebUiTest.class.getClassLoader().getResourceAsStream(path))
		{
			org.junit.jupiter.api.Assertions.assertNotNull(in, "resource not found on classpath: " + path);
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	@Test
	void emptyRepositoryShowsSetupInstructions() throws Exception
	{
		User owner = persistUser("ui-dora-" + unique());
		service.create(owner, "empty", Repository.Visibility.PUBLIC, null);

		given().when().get("/repos/" + owner.username + "/empty")
			.then().statusCode(200)
			.body(containsString("/git/" + owner.username + "/empty.git"))
			.body(containsString("git push"));
	}

	@Test
	void commitLogIsPaginated() throws Exception
	{
		User owner = persistUser("ui-eric-" + unique());
		Repository repo = service.create(owner, "logrepo", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("a.txt", "a\n".getBytes(StandardCharsets.UTF_8)), 3);

		String base = "/repos/" + owner.username + "/logrepo/commits/main";

		given().when().get(base + "?size=2")
			.then().statusCode(200)
			.body(containsString("commit 3"))
			.body(containsString("commit 2"))
			.body(not(containsString("commit 1")))
			.body(containsString("page=1"));

		given().when().get(base + "?size=2&page=1")
			.then().statusCode(200)
			.body(containsString("commit 1"))
			.body(not(containsString("commit 3")));
	}

	@Test
	void repositoryPagesRenderTabsWithActiveSection() throws Exception
	{
		User owner = persistUser("ui-tabs-" + unique());
		Repository repo = service.create(owner, "tabbed", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("a.txt", "a\n".getBytes(StandardCharsets.UTF_8)));

		String base = "/repos/" + owner.username + "/tabbed";

		given().when().get(base + "/tree/main")
			.then().statusCode(200)
			.body(containsString("class=\"tabs\""))
			.body(containsString("class=\"tab active\" href=\"" + base + "/tree/main/\">Files"))
			.body(containsString("class=\"tab\" href=\"" + base + "/commits/main\">Commits"))
			.body(containsString("class=\"tab\" href=\"" + base + "/branches\">Branches"));

		given().when().get(base + "/tree/main/a.txt")
			.then().statusCode(200)
			.body(containsString("class=\"tab active\" href=\"" + base + "/tree/main/\">Files"));

		given().when().get(base + "/commits/main")
			.then().statusCode(200)
			.body(containsString("class=\"tab active\" href=\"" + base + "/commits/main\">Commits"));

		given().when().get(base + "/branches")
			.then().statusCode(200)
			.body(containsString("class=\"tab active\" href=\"" + base + "/branches\">Branches"));
	}

	@Test
	void tabLinksPreserveSelectedRef() throws Exception
	{
		User owner = persistUser("ui-kate-" + unique());
		Repository repo = service.create(owner, "refkeep", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("a.txt", "a\n".getBytes(StandardCharsets.UTF_8)));
		try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.open(service.repositoryPath(repo).toFile()))
		{
			git.branchCreate().setName("feature").setStartPoint("main").call();
		}

		String base = "/repos/" + owner.username + "/refkeep";
		given().when().get(base + "/tree/feature")
			.then().statusCode(200)
			.body(containsString("class=\"tab active\" href=\"" + base + "/tree/feature/\">Files"))
			.body(containsString("href=\"" + base + "/commits/feature\">Commits"));
	}

	@Test
	@io.quarkus.test.security.TestSecurity(user = "ui-nojs")
	void formsWorkWithoutJavaScriptViaStandardPost() throws Exception
	{
		persistUser("ui-nojs");
		String name = "nojs-" + unique();

		// a plain form-encoded POST (what a browser sends with JS disabled) must complete the flow
		given().contentType("application/x-www-form-urlencoded")
			.formParam("name", name)
			.formParam("visibility", "PUBLIC")
			.formParam("description", "created without JS")
			.when().redirects().follow(false).post("/repos")
			.then().statusCode(org.hamcrest.Matchers.anyOf(
				org.hamcrest.Matchers.is(302), org.hamcrest.Matchers.is(303)));

		given().when().get("/repos/ui-nojs/" + name)
			.then().statusCode(200);

		// scripting is enhancement-only: loaded deferred, no inline handlers required for rendering
		given().when().get("/")
			.then().statusCode(200)
			.body(containsString("<script defer src=\"/shark-hotkeys.js\">"));
	}

	@Test
	void branchesPageMarksDefaultBranch() throws Exception
	{
		User owner = persistUser("ui-fred-" + unique());
		Repository repo = service.create(owner, "branched", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("a.txt", "a\n".getBytes(StandardCharsets.UTF_8)));

		given().when().get("/repos/" + owner.username + "/branched/branches")
			.then().statusCode(200)
			.body(containsString("main"))
			.body(containsString("default"));
	}

	@Test
	void repositoryPageShowsCloneUrls() throws Exception
	{
		User owner = persistUser("ui-gina-" + unique());
		Repository repo = service.create(owner, "cloneurls", Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("a.txt", "a\n".getBytes(StandardCharsets.UTF_8)));

		given().when().get("/repos/" + owner.username + "/cloneurls")
			.then().statusCode(200)
			.body(containsString("/git/" + owner.username + "/cloneurls.git"))
			.body(containsString("ssh://git@"));
	}

	@Test
	void repositoryOverviewShowsSidebarLatestCommitAndCounts() throws Exception
	{
		User owner = persistUser("ui-iris-" + unique());
		Repository repo = service.create(owner, "overview", Repository.Visibility.PUBLIC, "demo repo");
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("a.txt", "a\n".getBytes(StandardCharsets.UTF_8)), 3);

		given().when().get("/repos/" + owner.username + "/overview")
			.then().statusCode(200)
			.body(containsString("class=\"repo-side\""))
			.body(containsString("class=\"repo-nav\""))
			.body(containsString("class=\"commitrow\""))
			.body(containsString("commit 3"))
			.body(containsString("<b>3</b> commits"));
	}

	@Test
	void repositoryOverviewClonesViaDialogNotHeaderPill() throws Exception
	{
		User owner = persistUser("ui-jane-" + unique());
		Repository repo = service.create(owner, "clonedialog", Repository.Visibility.PUBLIC, "demo repo");
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("a.txt", "a\n".getBytes(StandardCharsets.UTF_8)));

		given().when().get("/repos/" + owner.username + "/clonedialog")
			.then().statusCode(200)
			// clone moved into a dialog opened by a button...
			.body(containsString("data-open-dialog=\"clone-dialog\""))
			.body(containsString("id=\"clone-dialog\""))
			// ...with both protocols selectable...
			.body(containsString("git clone "))
			.body(containsString("/git/" + owner.username + "/clonedialog.git"))
			.body(containsString("ssh://git@"))
			// ...and no longer pinned in the header topbar or sidebar
			.body(not(containsString("class=\"clonecmd\"")))
			.body(not(containsString("class=\"clone-urls\"")));
	}

	@Test
	@TestSecurity(user = "ui-mallory")
	void privateRepositoryHiddenFromStranger() throws Exception
	{
		persistUser("ui-mallory");
		User owner = persistUser("ui-henry-" + unique());
		service.create(owner, "hidden", Repository.Visibility.PRIVATE, null);

		given().when().get("/repos/" + owner.username + "/hidden")
			.then().statusCode(404);
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
