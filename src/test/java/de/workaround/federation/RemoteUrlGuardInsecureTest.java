package de.workaround.federation;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * With the dev-only insecure flag on, the SSRF guard permits http + loopback targets (so two
 * instances can federate on one machine) but STILL enforces the peer allowlist.
 */
@QuarkusTest
@TestProfile(RemoteUrlGuardInsecureTest.InsecureProfile.class)
class RemoteUrlGuardInsecureTest
{
	public static class InsecureProfile implements QuarkusTestProfile
	{
		@Override
		public Map<String, String> getConfigOverrides()
		{
			return Map.of(
				"gitshark.federation.enabled", "true",
				"gitshark.federation.base-url", "https://shark.test",
				"gitshark.federation.dev-allow-insecure", "true",
				"gitshark.federation.peer-allowlist", "localhost,127.0.0.1,shark-b.local");
		}
	}

	@Inject
	RemoteUrlGuard guard;

	@Test
	void httpAndLoopbackAllowedWhenInsecureAndAllowlisted()
	{
		assertDoesNotThrow(() -> guard.requireSafe("http://localhost:8081/ap/instance/inbox"));
		assertDoesNotThrow(() -> guard.requireSafe("https://shark-b.local:8443/ap/instance/inbox"));
	}

	@Test
	void allowlistStillEnforcedInInsecureMode()
	{
		assertThrows(RemoteUrlGuard.UnsafeUrlException.class,
			() -> guard.requireSafe("http://evil.example/ap/users/x"));
	}

}
