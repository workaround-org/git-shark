package de.workaround.http;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.AccessToken;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AccessTokenServiceTest
{
	@Inject
	AccessTokenService service;

	@Inject
	AccessToken.Repo tokens;

	@Test
	@TestTransaction
	void createReturnsPlaintextOnceAndStoresOnlyHash()
	{
		User user = persistUser();

		AccessTokenService.CreatedToken created = service.create(user, "ci");

		assertTrue(created.plaintext().startsWith("gs_"), "token has recognizable prefix");
		AccessToken stored = tokens.findByTokenHash(service.hash(created.plaintext())).orElseThrow();
		assertEquals("ci", stored.label);
		assertNotEquals(created.plaintext(), stored.tokenHash, "plaintext must never be persisted");
	}

	@Test
	@TestTransaction
	void authenticatesValidTokenToItsUser()
	{
		User user = persistUser();
		AccessTokenService.CreatedToken created = service.create(user, "ci");

		User authenticated = service.authenticate(created.plaintext()).orElseThrow();

		assertEquals(user.id, authenticated.id);
	}

	@Test
	@TestTransaction
	void rejectsUnknownToken()
	{
		assertTrue(service.authenticate("gs_does-not-exist").isEmpty());
	}

	@Test
	@TestTransaction
	void revokedTokenNoLongerAuthenticates()
	{
		User user = persistUser();
		AccessTokenService.CreatedToken created = service.create(user, "ci");

		service.revoke(user, created.token().id);

		assertTrue(service.authenticate(created.plaintext()).isEmpty());
	}

	private static User persistUser()
	{
		String name = "token-" + UUID.randomUUID();
		User user = new User();
		user.oidcSub = "sub-" + name;
		user.username = name;
		user.persist();
		return user;
	}

}
