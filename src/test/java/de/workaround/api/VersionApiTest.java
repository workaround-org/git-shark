package de.workaround.api;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.notNullValue;

@QuarkusTest
class VersionApiTest
{
	@Test
	void versionIsReadableAnonymously()
	{
		given().when().get("/api/v1/version")
			.then().statusCode(200)
			.body("version", notNullValue());
	}
}
