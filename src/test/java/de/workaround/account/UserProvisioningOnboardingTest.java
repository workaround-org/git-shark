package de.workaround.account;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
class UserProvisioningOnboardingTest
{
	@Inject
	UserProvisioningService service;

	@Inject
	User.Repo users;

	@Test
	@TestTransaction
	void oidcFirstLoginCreatesUserWithoutHandle()
	{
		String sub = "sub-" + UUID.randomUUID();

		User user = service.provisionFromOidc(sub, "miggi@sso.mymiggi.de", "Miggi", "miggi@example.com");

		assertNull(user.username, "handle must not be derived from the claim; onboarding chooses it");
		User stored = users.findByOidcSub(sub);
		assertNull(stored.username);
		assertEquals("Miggi", stored.displayName);
		assertEquals("miggi@example.com", stored.email);
	}

	@Test
	@TestTransaction
	void returningUserKeepsChosenHandle()
	{
		String sub = "sub-" + UUID.randomUUID();
		User created = service.provisionFromOidc(sub, "claim", "Name", "e@example.com");
		created.username = "chosen-handle";

		User again = service.provisionFromOidc(sub, "claim", "New Name", "new@example.com");

		assertEquals("chosen-handle", again.username, "chosen handle survives re-login");
		assertEquals("New Name", again.displayName);
	}
}
