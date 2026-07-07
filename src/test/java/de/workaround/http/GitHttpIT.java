package de.workaround.http;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusIntegrationTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the git smart-HTTP transport and repository browsing against the packaged
 * application (JVM jar or native binary). The seeded alice/demo repository (enabled via
 * GITSHARK_DEV_SEED_DATA in the failsafe config) provides the data; JGit acts as a real
 * client, so this covers the servlet, UploadPack, JGit object I/O and the DB lookup path
 * in the native image.
 */
@QuarkusIntegrationTest
class GitHttpIT
{
	private static String cloneUrl()
	{
		int port = Integer.getInteger("quarkus.http.test-port", 8081);
		return "http://localhost:" + port + "/git/alice/demo.git";
	}

	@Test
	void anonymousCloneOfSeededPublicRepositorySucceeds() throws Exception
	{
		Path target = Files.createTempDirectory("it-clone");
		try (Git clone = Git.cloneRepository().setURI(cloneUrl()).setDirectory(target.toFile()).call())
		{
			assertTrue(Files.exists(target.resolve("README.md")), "clone must contain the seeded README");
			assertTrue(Files.readString(target.resolve("README.md")).contains("demo"));
		}
	}

	@Test
	void anonymousPushIsRejectedAndDoesNotAlterTheRepository() throws Exception
	{
		Path work = Files.createTempDirectory("it-push");
		try (Git git = Git.cloneRepository().setURI(cloneUrl()).setDirectory(work.toFile()).call())
		{
			Files.writeString(work.resolve("evil.txt"), "anonymous write\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("anonymous push attempt").setSign(false)
				.setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
			assertThrows(TransportException.class,
				() -> git.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call());
		}

		Path recheck = Files.createTempDirectory("it-recheck");
		try (Git clone = Git.cloneRepository().setURI(cloneUrl()).setDirectory(recheck.toFile()).call())
		{
			assertFalse(Files.exists(recheck.resolve("evil.txt")), "rejected push must not change refs");
		}
	}

	@Test
	void repositoryOverviewPageRenders()
	{
		given()
			.when().get("/repos/alice/demo")
			.then()
			.statusCode(200)
			.body(containsString("demo"));
	}

	@Test
	void rawFileIsServedFromTheSeededRepository()
	{
		given()
			.when().get("/repos/alice/demo/raw/main/README.md")
			.then()
			.statusCode(200)
			.body(containsString("demo"));
	}

	@Test
	void blobPageRendersSeededFileWithHighlightMarkup()
	{
		given()
			.when().get("/repos/alice/demo/tree/main/README.md")
			.then()
			.statusCode(200)
			.body(containsString("demo"))
			.body(containsString("<code"));
	}

	@Test
	void vendoredHighlightAssetIsServed()
	{
		given()
			.when().get("/highlight.min.js")
			.then()
			.statusCode(200);
	}

}
