package de.workaround;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

/**
 * Boots the packaged application (JVM jar or native binary) and verifies the service is alive:
 * public pages (Qute templates) rendering, static design-system assets served, and the embedded
 * SSH server accepting connections. Health and OpenAPI live on the management interface
 * (%prod.quarkus.management.enabled=true → a separate port), so they are not asserted here.
 */
@QuarkusIntegrationTest
class SmokeIT
{
	@Test
	void landingPageRenders()
	{
		given()
			.when().get("/")
			.then()
			.statusCode(200);
	}

	@Test
	void explorePageRenders()
	{
		given()
			.when().get("/explore")
			.then()
			.statusCode(200);
	}

	@Test
	void designSystemAssetsAreServed()
	{
		given()
			.when().get("/shark.css")
			.then()
			.statusCode(200)
			.body(org.hamcrest.CoreMatchers.containsString(":root"));

		given()
			.when().get("/shark-hotkeys.js")
			.then()
			.statusCode(200);
	}

	@Test
	void sshPortPresentsSshBanner() throws Exception
	{
		int port = Integer.getInteger("gitshark.ssh.port", 2222);
		try (java.net.Socket socket = new java.net.Socket("localhost", port))
		{
			byte[] banner = new byte[7];
			int read = socket.getInputStream().readNBytes(banner, 0, banner.length);
			org.junit.jupiter.api.Assertions.assertEquals(7, read);
			org.junit.jupiter.api.Assertions.assertEquals("SSH-2.0",
				new String(banner, java.nio.charset.StandardCharsets.US_ASCII));
		}
	}

}
