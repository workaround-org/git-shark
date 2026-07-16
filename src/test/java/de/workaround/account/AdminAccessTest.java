package de.workaround.account;

import org.junit.jupiter.api.Test;

import de.workaround.git.ForbiddenOperationException;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AdminAccessTest
{
	@Inject
	AdminAccess adminAccess;

	@Test
	void configuredHandleIsAdmin()
	{
		// test profile sets gitshark.admin.handles=alice
		assertTrue(adminAccess.isAdmin(user("alice")));
		assertDoesNotThrow(() -> adminAccess.require(user("alice")));
	}

	@Test
	void otherHandlesAreNotAdmin()
	{
		assertFalse(adminAccess.isAdmin(user("bob")));
		assertFalse(adminAccess.isAdmin(null));
		assertThrows(ForbiddenOperationException.class, () -> adminAccess.require(user("bob")));
	}

	private static User user(String username)
	{
		User user = new User();
		user.username = username;
		return user;
	}
}
