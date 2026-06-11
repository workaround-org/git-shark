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
	void anonymousSeesOnlyPublicRepositoriesOnHome() throws Exception
	{
		User owner = persistUser("ui-bob-" + unique());
		service.create(owner, "ui-pub", Repository.Visibility.PUBLIC, "public demo");
		service.create(owner, "ui-priv", Repository.Visibility.PRIVATE, null);

		given()
			.when().get("/")
			.then()
			.statusCode(200)
			.body(containsString("ui-pub"))
			.body(not(containsString("ui-priv")));
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
