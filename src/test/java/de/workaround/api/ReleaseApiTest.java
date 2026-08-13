package de.workaround.api;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
import de.workaround.http.AccessTokenService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;

@QuarkusTest
class ReleaseApiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	AccessTokenService tokenService;

	@Inject
	User.Repo userRepo;

	@Test
	void createListGetUpdateAndDeleteARelease() throws Exception
	{
		User owner = persistUser("api-release-owner");
		String token = mintToken(owner);
		Repository repo = seededRepo(owner, "released");
		String base = "/api/v1/repos/" + owner.username + "/released/releases";

		// create — the tag is cut from target_commitish when it does not exist yet
		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("tag_name", "v1.0.0", "target_commitish", "main", "name", "1.0.0", "body", "notes"))
			.when().post(base)
			.then().statusCode(201)
			.body("tag_name", equalTo("v1.0.0"))
			.body("name", equalTo("1.0.0"))
			.body("body", equalTo("notes"))
			.body("prerelease", equalTo(false))
			.body("draft", equalTo(false))
			.body("author.login", equalTo(owner.username))
			.body("zipball_url", containsString("/repos/" + owner.username + "/released/archive/v1.0.0.zip"))
			.body("tarball_url", containsString("/repos/" + owner.username + "/released/archive/v1.0.0.tar.gz"));

		// list (anonymous read on a public repo)
		given().when().get(base)
			.then().statusCode(200)
			.body("tag_name", hasItem("v1.0.0"));

		// get by tag
		given().when().get(base + "/tags/v1.0.0")
			.then().statusCode(200)
			.body("name", equalTo("1.0.0"));

		// latest
		given().when().get(base + "/latest")
			.then().statusCode(200)
			.body("tag_name", equalTo("v1.0.0"));

		// update
		Map<String, Object> edit = new HashMap<>();
		edit.put("name", "1.0.0 final");
		edit.put("body", "shipped");
		edit.put("prerelease", true);
		given().header("Authorization", "Bearer " + token)
			.contentType("application/json").body(edit)
			.when().patch(base + "/tags/v1.0.0")
			.then().statusCode(200)
			.body("name", equalTo("1.0.0 final"))
			.body("prerelease", equalTo(true));

		// a prerelease is not the latest release
		given().when().get(base + "/latest").then().statusCode(404);

		// delete
		given().header("Authorization", "Bearer " + token)
			.when().delete(base + "/tags/v1.0.0")
			.then().statusCode(204);
		given().when().get(base + "/tags/v1.0.0").then().statusCode(404);
	}

	@Test
	void anonymousCannotCreateReleases() throws Exception
	{
		User owner = persistUser("api-release-anon");
		seededRepo(owner, "readonly");

		given().contentType("application/json")
			.body(Map.of("tag_name", "v1.0.0", "target_commitish", "main", "name", "1.0.0"))
			.when().post("/api/v1/repos/" + owner.username + "/readonly/releases")
			.then().statusCode(401);
	}

	@Test
	void anUnknownTagWithoutATargetIsRejected() throws Exception
	{
		User owner = persistUser("api-release-badtag");
		String token = mintToken(owner);
		seededRepo(owner, "picky");

		given().header("Authorization", "Bearer " + token)
			.contentType("application/json")
			.body(Map.of("tag_name", "v9.9.9", "name", "9.9.9"))
			.when().post("/api/v1/repos/" + owner.username + "/picky/releases")
			.then().statusCode(400);
	}

	private Repository seededRepo(User owner, String name) throws Exception
	{
		Repository repo = service.create(owner, name, Repository.Visibility.PUBLIC, null);
		GitTestSeeder.seed(service.repositoryPath(repo),
			Map.of("README.md", "# hi\n".getBytes(StandardCharsets.UTF_8)));
		return repo;
	}

	private String mintToken(User owner)
	{
		return tokenService.create(owner, "release-api").plaintext();
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
