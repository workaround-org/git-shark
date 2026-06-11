package de.workaround;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.is;

/**
 * Boots the packaged application (JVM jar or native binary) and verifies the service is alive:
 * HTTP health endpoint up and the embedded SSH server accepting connections.
 */
@QuarkusIntegrationTest
class SmokeIT
{
	@Test
	void healthEndpointIsUp()
	{
		given()
			.when().get("/q/health")
			.then()
			.statusCode(200)
			.body("status", is("UP"));
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
