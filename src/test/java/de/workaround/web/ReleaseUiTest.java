package de.workaround.web;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
import de.workaround.git.ReleaseService;
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

@QuarkusTest
class ReleaseUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	ReleaseService releases;

	@Inject
	User.Repo users;

	@Test
	@TestSecurity(user = "rel-ui-owner")
	void ownerCanPublishBrowseEditAndDeleteARelease() throws Exception
	{
		User owner = persistUser("rel-ui-owner");
		Repository repo = seededRepo(owner, "shipit");
		String base = "/repos/" + owner.username + "/shipit/releases";

		// empty state
		given().when().get(base).then().statusCode(200).body(containsString("No releases"));

		// publish, creating the tag from the default branch on the way
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("tagName", "v1.0.0").formParam("target", "main")
			.formParam("title", "1.0.0").formParam("body", "## Highlights\n\n- first cut")
			.when().post(base)
			.then().statusCode(303)
			.header("Location", containsString("/releases/tag/v1.0.0"));

		// the list shows the release, its tag and the Latest badge
		given().when().get(base)
			.then().statusCode(200)
			.body(containsString("1.0.0"))
			.body(containsString("v1.0.0"))
			.body(containsString("Latest"));

		// the detail page renders the notes as Markdown and offers both source archives
		given().when().get(base + "/tag/v1.0.0")
			.then().statusCode(200)
			.body(containsString("<h2>Highlights</h2>"))
			.body(containsString("first cut"))
			.body(containsString("/archive/v1.0.0.zip"))
			.body(containsString("/archive/v1.0.0.tar.gz"));

		// edit
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("title", "1.0.0 final").formParam("body", "shipped").formParam("prerelease", "on")
			.when().post(base + "/tag/v1.0.0/edit")
			.then().statusCode(303);
		given().when().get(base + "/tag/v1.0.0")
			.then().statusCode(200)
			.body(containsString("1.0.0 final"))
			.body(containsString("Pre-release"));

		// delete
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.when().post(base + "/tag/v1.0.0/delete")
			.then().statusCode(303);
		given().when().get(base).then().statusCode(200).body(containsString("No releases"));
	}

	@Test
	@TestSecurity(user = "rel-ui-nav")
	void theSidebarLinksReleasesWithTheirCount() throws Exception
	{
		User owner = persistUser("rel-ui-nav");
		Repository repo = seededRepo(owner, "navved");
		releases.create(owner, repo, "v0.1.0", "main", "0.1.0", null, false);

		given().when().get("/repos/" + owner.username + "/navved")
			.then().statusCode(200)
			.body(containsString("/repos/" + owner.username + "/navved/releases"))
			.body(containsString("Releases"));
	}

	@Test
	void sourceArchivesDownloadForAnyoneWhoCanReadTheRepo() throws Exception
	{
		User owner = persistUser("rel-ui-archive");
		Repository repo = seededRepo(owner, "archived");
		releases.create(owner, repo, "v1.0.0", "main", "1.0.0", null, false);
		String base = "/repos/" + owner.username + "/archived";

		byte[] zip = given().when().get(base + "/archive/v1.0.0.zip")
			.then().statusCode(200)
			.header("Content-Disposition", containsString("archived-v1.0.0.zip"))
			.extract().asByteArray();
		assertEquals(List.of("archived-v1.0.0/README.md"), zipEntries(zip));

		byte[] tarGz = given().when().get(base + "/archive/v1.0.0.tar.gz")
			.then().statusCode(200)
			.header("Content-Disposition", containsString("archived-v1.0.0.tar.gz"))
			.extract().asByteArray();
		assertTrue(tarNames(tarGz).contains("archived-v1.0.0/README.md"), "tar contains the seeded README");
	}

	@Test
	@TestSecurity(user = "rel-ui-stranger")
	void aStrangerSeesNoPublishButtonAndCannotPost() throws Exception
	{
		User owner = persistUser("rel-ui-guarded-owner");
		persistUser("rel-ui-stranger");
		Repository repo = seededRepo(owner, "guarded");
		String base = "/repos/" + owner.username + "/guarded/releases";

		given().when().get(base).then().statusCode(200).body(not(containsString("New release")));
		given().redirects().follow(false)
			.contentType("application/x-www-form-urlencoded")
			.formParam("tagName", "v1.0.0").formParam("target", "main").formParam("title", "nope")
			.when().post(base)
			.then().statusCode(403);
	}

	private static List<String> zipEntries(byte[] zip) throws Exception
	{
		List<String> names = new ArrayList<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip)))
		{
			for (ZipEntry entry = in.getNextEntry(); entry != null; entry = in.getNextEntry())
			{
				names.add(entry.getName());
			}
		}
		return names;
	}

	/** Reads the 100-byte name field out of every 512-byte ustar header block. */
	private static List<String> tarNames(byte[] tarGz) throws Exception
	{
		byte[] tar;
		try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(tarGz)))
		{
			tar = in.readAllBytes();
		}
		List<String> names = new ArrayList<>();
		for (int offset = 0; offset + 512 <= tar.length; offset += 512)
		{
			String name = new String(tar, offset, 100, StandardCharsets.UTF_8).replace("\0", "");
			if (name.isEmpty())
			{
				break;
			}
			names.add(name);
			int size = Integer.parseInt(new String(tar, offset + 124, 12, StandardCharsets.UTF_8)
				.replace("\0", "").strip(), 8);
			offset += (size + 511) / 512 * 512;
		}
		return names;
	}

	private Repository seededRepo(User owner, String name) throws Exception
	{
		Repository repo = service.create(owner, name, Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("README.md", "# hi\n".getBytes(StandardCharsets.UTF_8)));
		return repo;
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
