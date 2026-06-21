package de.workaround.federation;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SSRF guard: allowlist + scheme enforcement (bean) and the non-public address check (pure). */
@QuarkusTest
class RemoteUrlGuardTest
{
	@Inject
	RemoteUrlGuard guard;

	@Test
	void nonAllowlistedHostIsRefused()
	{
		assertThrows(RemoteUrlGuard.UnsafeUrlException.class,
			() -> guard.requireSafe("https://evil.example/ap/users/x"));
	}

	@Test
	void nonHttpsIsRefused()
	{
		assertThrows(RemoteUrlGuard.UnsafeUrlException.class,
			() -> guard.requireSafe("http://peer.test/ap/users/x"));
	}

	@Test
	void malformedUrlIsRefused()
	{
		assertThrows(RemoteUrlGuard.UnsafeUrlException.class, () -> guard.requireSafe("not a url"));
	}

	@Test
	void privateAndLoopbackAddressesAreNonPublic() throws Exception
	{
		assertTrue(RemoteUrlGuard.isNonPublic(InetAddress.getByName("127.0.0.1")));
		assertTrue(RemoteUrlGuard.isNonPublic(InetAddress.getByName("10.0.0.1")));
		assertTrue(RemoteUrlGuard.isNonPublic(InetAddress.getByName("192.168.1.5")));
		assertTrue(RemoteUrlGuard.isNonPublic(InetAddress.getByName("169.254.1.1")));
		assertFalse(RemoteUrlGuard.isNonPublic(InetAddress.getByName("8.8.8.8")));
	}

}
