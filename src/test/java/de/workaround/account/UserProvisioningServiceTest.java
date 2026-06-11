package de.workaround.account;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class UserProvisioningServiceTest
{
	@Inject
	UserProvisioningService service;

	@Inject
	User.Repo users;

	@Test
	@TestTransaction
	void firstLoginCreatesUserKeyedByOidcSub()
	{
		String sub = "sub-" + UUID.randomUUID();

		User user = service.provision(sub, "alice", "Alice A.", "alice@example.com");

		assertNotNull(user.id);
		User stored = users.findByOidcSub(sub);
		assertEquals("alice", stored.username);
		assertEquals("Alice A.", stored.displayName);
		assertEquals("alice@example.com", stored.email);
	}

	@Test
	@TestTransaction
	void subsequentLoginUpdatesProfileWithoutDuplicate()
	{
		String sub = "sub-" + UUID.randomUUID();
		User first = service.provision(sub, "bob", "Bob", "bob@example.com");

		User second = service.provision(sub, "bob", "Bobby", "bobby@example.com");

		assertEquals(first.id, second.id, "no duplicate user");
		User stored = users.findByOidcSub(sub);
		assertEquals("Bobby", stored.displayName);
		assertEquals("bobby@example.com", stored.email);
	}

}
