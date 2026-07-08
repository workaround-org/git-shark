package de.workaround.api;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusIntegrationTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Exercises the JSON REST API against the packaged application (JVM jar or native binary). The seeded
 * alice/demo repository — public, with a demo merge request carrying a line comment (enabled via
 * GITSHARK_DEV_SEED_DATA in the failsafe config) — lets anonymous reads drive the native-sensitive path:
 * JAX-RS routing, the {@code /api} bearer-token filter, and Jackson serialization of the response records.
 * Token-authenticated writes stay in the JVM-only {@code *Test} classes (a black-box IT cannot mint a token).
 */
@QuarkusIntegrationTest
class ApiSmokeIT
{
	@Test
	void repositoryListIncludesTheSeededPublicRepository()
	{
		given()
			.when().get("/api/v1/repos")
			.then()
			.statusCode(200)
			.contentType("application/json")
			.body("name", hasItem("demo"));
	}

	@Test
	void repositoryDetailIsServedAsJson()
	{
		given()
			.when().get("/api/v1/repos/alice/demo")
			.then()
			.statusCode(200)
			.contentType("application/json")
			.body("owner", equalTo("alice"))
			.body("name", equalTo("demo"))
			.body("visibility", equalTo("PUBLIC"));
	}

	@Test
	void issuesListIsServedAsJson()
	{
		given()
			.when().get("/api/v1/repos/alice/demo/issues")
			.then()
			.statusCode(200)
			.contentType("application/json")
			.body("size()", greaterThanOrEqualTo(0));
	}

	@Test
	void mergeRequestAndCommentViewsSerialize()
	{
		// the seeder creates one demo merge request with a line comment; both must serialize under native
		int number = given()
			.when().get("/api/v1/repos/alice/demo/merge-requests")
			.then()
			.statusCode(200)
			.contentType("application/json")
			.body("size()", greaterThanOrEqualTo(1))
			.extract().path("[0].number");

		given()
			.when().get("/api/v1/repos/alice/demo/merge-requests/" + number + "/comments")
			.then()
			.statusCode(200)
			.contentType("application/json")
			.body("size()", greaterThanOrEqualTo(1));
	}

	@Test
	void currentUserWithoutTokenIsUnauthorized()
	{
		given()
			.when().get("/api/v1/user")
			.then()
			.statusCode(401);
	}
}
