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
import io.restassured.response.Response;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With a captcha configured, an over-budget anonymous visitor is sent to {@code /challenge} instead
 * of a dead end; solving it mints a signed pass cookie that lifts the budget for its lifetime. A
 * rejected token mints nothing, and the post-solve redirect can never leave the instance.
 */
@QuarkusTest
@TestProfile(ChallengeFlowTest.CaptchaProfile.class)
class ChallengeFlowTest
{
	public static class CaptchaProfile implements QuarkusTestProfile
	{
		@Override
		public Map<String, String> getConfigOverrides()
		{
			return Map.of(
				"gitshark.protect.enabled", "true",
				"gitshark.protect.anonymous-limit", "1",
				"gitshark.protect.window", "1m",
				"gitshark.protect.captcha.provider", "turnstile",
				"gitshark.protect.captcha.site-key", "test-site-key",
				"gitshark.protect.captcha.secret-key", "test-secret-key",
				"gitshark.protect.captcha.verify-url", "http://localhost:8081/test/siteverify",
				"gitshark.protect.captcha.pass-duration", "30m");
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
	void overBudgetVisitorsAreSentToTheChallengePage()
	{
		String commit = seedCommitPath("ch-anon");

		given().when().get(commit).then().statusCode(200);

		String location = given().redirects().follow(false).when().get(commit)
			.then().statusCode(303)
			.extract().header("Location");
		assertTrue(location.contains("/challenge?redirect="), "expected a challenge redirect, got " + location);

		given().when().get(location)
			.then().statusCode(200)
			.body(containsString("cf-turnstile"))
			.body(containsString("test-site-key"));
	}

	@Test
	void solvingTheChallengeMintsAPassThatLiftsTheBudget()
	{
		String commit = seedCommitPath("ch-solve");
		given().when().get(commit).then().statusCode(200);

		Response solved = given().redirects().follow(false)
			.formParam("redirect", commit)
			.formParam("cf-turnstile-response", "good-token")
			.when().post("/challenge")
			.then().statusCode(303)
			// JAX-RS absolutizes Location; only the target path is interesting here
			.header("Location", endsWith(commit))
			.extract().response();

		String pass = solved.getCookie("gitshark_human");
		assertNotNull(pass, "solving the challenge must set the pass cookie");

		given().cookie("gitshark_human", pass).when().get(commit).then().statusCode(200);
		given().cookie("gitshark_human", pass).when().get(commit).then().statusCode(200);
	}

	@Test
	void aRejectedTokenMintsNoPass()
	{
		Response rejected = given().redirects().follow(false)
			.formParam("redirect", "/repos/ch-reject/board")
			.formParam("cf-turnstile-response", "bad-token")
			.when().post("/challenge")
			.then().statusCode(403)
			.extract().response();

		assertNull(rejected.getCookie("gitshark_human"));
	}

	@Test
	void theRedirectAfterSolvingStaysOnThisInstance()
	{
		given().redirects().follow(false)
			.formParam("redirect", "https://evil.example/phish")
			.formParam("cf-turnstile-response", "good-token")
			.when().post("/challenge")
			.then().statusCode(303)
			.header("Location", endsWith("/"))
			.header("Location", not(containsString("evil.example")));
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
