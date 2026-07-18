package de.workaround.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitBrowseService;
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

@QuarkusTest
class CommitDiffUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	GitBrowseService browse;

	@Inject
	User.Repo userRepo;

	@Test
	@TestSecurity(user = "cd-owner")
	void commitDetailShowsTheDiffAndTheCommitsListLinksToIt() throws Exception
	{
		User owner = persistUser("cd-owner");
		Repository repo = service.create(owner, "board", Repository.Visibility.PUBLIC, null);
		Path bare = service.repositoryPath(repo);
		// two commits: the second rewrites counter.txt to "revision 2\n"
		GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)), 2);
		String head = browse.commits(bare, "main", 0, 10).orElseThrow().commits().get(0).id();

		String detail = "/repos/" + owner.username + "/board/commit/" + head;
		given().when().get(detail)
			.then().statusCode(200)
			.body(containsString("counter.txt"))
			.body(containsString("revision 2"));

		// the commits list links each row to its per-commit diff page
		given().when().get("/repos/" + owner.username + "/board/commits/main")
			.then().statusCode(200)
			.body(containsString("/board/commit/" + head));
	}

	@Test
	@TestSecurity(user = "cd-root")
	void rootCommitShowsItsFilesAsAdditions() throws Exception
	{
		User owner = persistUser("cd-root");
		Repository repo = service.create(owner, "seed", Repository.Visibility.PUBLIC, null);
		Path bare = service.repositoryPath(repo);
		GitTestSeeder.seed(bare, Map.of("hello.txt", "hello world\n".getBytes(StandardCharsets.UTF_8)));
		String root = browse.commits(bare, "main", 0, 10).orElseThrow().commits().get(0).id();

		given().when().get("/repos/" + owner.username + "/seed/commit/" + root)
			.then().statusCode(200)
			.body(containsString("hello.txt"))
			.body(containsString("hello world"));
	}

	@Test
	void unknownCommitIs404()
	{
		User owner = persistUser("cd-404-owner");
		service.create(owner, "empty", Repository.Visibility.PUBLIC, null);

		given().when().get("/repos/" + owner.username + "/empty/commit/0000000000000000000000000000000000000000")
			.then().statusCode(404);
	}

	@Test
	@TestSecurity(user = "cd-stranger")
	void privateRepoCommitIsHiddenFromStrangers() throws Exception
	{
		persistUser("cd-stranger");
		User owner = persistUser("cd-priv-owner");
		Repository repo = service.create(owner, "secret", Repository.Visibility.PRIVATE, null);
		Path bare = service.repositoryPath(repo);
		GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)));
		String head = browse.commits(bare, "main", 0, 10).orElseThrow().commits().get(0).id();

		given().when().get("/repos/" + owner.username + "/secret/commit/" + head)
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
