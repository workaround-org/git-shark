package de.workaround.federation;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

import static io.restassured.RestAssured.given;

/**
 * Section 3: the inbox rejects unsigned, malformed, and off-allowlist requests with 401 before any
 * processing. The valid-signature happy path is exercised by the two-instance test in Section 6.
 */
@QuarkusTest
class InboxAuthTest
{
	private static final String ACTIVITY = "application/activity+json";

	@Test
	void unsignedRequestIsRejected()
	{
		given().contentType(ACTIVITY)
			.body("{\"type\":\"Follow\",\"id\":\"https://peer.test/a/1\"}")
			.when().post("/ap/instance/inbox")
			.then().statusCode(401);
	}

	@Test
	void malformedSignatureIsRejected()
	{
		given().contentType(ACTIVITY)
			.header("Signature", "this-is-not-a-valid-signature-header")
			.body("{\"type\":\"Follow\",\"id\":\"https://peer.test/a/2\"}")
			.when().post("/ap/instance/inbox")
			.then().statusCode(401);
	}

	@Test
	void offAllowlistSignerIsRejected()
	{
		String signature = "keyId=\"https://evil.example/ap/users/x#main-key\",algorithm=\"rsa-sha256\","
			+ "headers=\"(request-target) host date digest\",signature=\"AAAA\"";
		given().contentType(ACTIVITY)
			.header("Signature", signature)
			.body("{\"type\":\"Follow\",\"id\":\"https://evil.example/a/3\"}")
			.when().post("/ap/instance/inbox")
			.then().statusCode(401);
	}

}
