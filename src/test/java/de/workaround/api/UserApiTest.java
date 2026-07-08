package de.workaround.api;

import org.junit.jupiter.api.Test;

import de.workaround.http.AccessTokenService;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;

@QuarkusTest
class UserApiTest
{
	@Inject
	AccessTokenService tokenService;

	@Inject
	User.Repo userRepo;

	@Test
	void currentUserReturnsTheTokenOwner()
	{
		User user = persistUser("api-me");
		String token = tokenService.create(user, "api-test").plaintext();

		given().header("Authorization", "Bearer " + token)
			.when().get("/api/v1/user")
			.then().statusCode(200)
			.body("username", equalTo("api-me"));
	}

	@Test
	void currentUserWithoutTokenIsUnauthorized()
	{
		given().when().get("/api/v1/user")
			.then().statusCode(401);
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
