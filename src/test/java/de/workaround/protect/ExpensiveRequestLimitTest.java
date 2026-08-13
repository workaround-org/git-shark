package de.workaround.protect;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitBrowseService;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.notNullValue;

/**
 * Anonymous visitors get a small budget of expensive renderings per window; logged-in users get the
 * larger one. With no captcha configured the refusal is a plain 429 — the instance never depends on
 * a third-party widget to be able to say no.
 */
@QuarkusTest
@TestProfile(ExpensiveRequestLimitTest.TightLimitProfile.class)
class ExpensiveRequestLimitTest
{
	public static class TightLimitProfile implements QuarkusTestProfile
	{
		@Override
		public Map<String, String> getConfigOverrides()
		{
			return Map.of(
				"gitshark.protect.enabled", "true",
				"gitshark.protect.anonymous-limit", "2",
				"gitshark.protect.user-limit", "50",
				"gitshark.protect.window", "1m",
				"gitshark.protect.captcha.provider", "none");
		}
	}

	@Inject
	GitRepositoryService service;

	@Inject
	GitBrowseService browse;

	@Inject
	RateLimiter limiter;

	@Inject
	User.Repo userRepo;

	@BeforeEach
	void clearBudgets()
	{
		limiter.reset();
	}

	@Test
	void anonymousRequestsBeyondTheLimitAreRefusedWith429()
	{
		String commit = seedCommitPath("rl-anon");

		given().when().get(commit).then().statusCode(200);
		given().when().get(commit).then().statusCode(200);
		given().when().get(commit)
			.then().statusCode(429)
			.header("Retry-After", notNullValue())
			.header("Cache-Control", containsString("no-store"));
	}

	@Test
	@TestSecurity(user = "rl-user")
	void loggedInUsersGetTheLargerBudget()
	{
		String commit = seedCommitPath("rl-user");

		for (int i = 0; i < 5; i++)
		{
			given().when().get(commit).then().statusCode(200);
		}
	}

	@Test
	void cheapPagesStayReachableAfterTheBudgetIsGone()
	{
		String commit = seedCommitPath("rl-cheap");
		String overview = "/repos/rl-cheap/board";

		given().when().get(commit).then().statusCode(200);
		given().when().get(commit).then().statusCode(200);
		given().when().get(commit).then().statusCode(429);

		given().when().get(overview).then().statusCode(200);
		given().when().get(overview + "/branches").then().statusCode(200);
	}

	private String seedCommitPath(String handle)
	{
		User owner = persistUser(handle);
		Repository repo = service.create(owner, "board", Repository.Visibility.PUBLIC, null);
		Path bare = service.repositoryPath(repo);
		try
		{
			GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)), 2);
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
		String head = browse.commits(bare, "main", 0, 10).orElseThrow().commits().get(0).id();
		return "/repos/" + handle + "/board/commit/" + head;
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
