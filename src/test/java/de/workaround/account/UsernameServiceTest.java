package de.workaround.account;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class UsernameServiceTest
{
	@Inject
	UsernameService usernames;

	@Inject
	User.Repo users;

	private User onboardingPendingUser()
	{
		User user = new User();
		user.oidcSub = "sub-" + UUID.randomUUID();
		user.persist();
		return user;
	}

	@Test
	@TestTransaction
	void acceptsValidUniqueHandle()
	{
		User user = onboardingPendingUser();
		String handle = "handle-" + UUID.randomUUID().toString().substring(0, 8);

		usernames.choose(user, handle);

		assertEquals(handle, users.findById(user.id).username);
	}

	@Test
	@TestTransaction
	void rejectsInvalidCharset()
	{
		User user = onboardingPendingUser();
		assertThrows(InvalidUsernameException.class, () -> usernames.choose(user, "Bad.Name"));
	}

	@Test
	@TestTransaction
	void rejectsLeadingHyphen()
	{
		User user = onboardingPendingUser();
		assertThrows(InvalidUsernameException.class, () -> usernames.choose(user, "-nope"));
	}

	@Test
	@TestTransaction
	void rejectsTooLongHandle()
	{
		User user = onboardingPendingUser();
		assertThrows(InvalidUsernameException.class, () -> usernames.choose(user, "a".repeat(40)));
	}

	@Test
	@TestTransaction
	void rejectsTakenHandle()
	{
		String taken = "taken-" + UUID.randomUUID().toString().substring(0, 8);
		User existing = onboardingPendingUser();
		usernames.choose(existing, taken);

		User newcomer = onboardingPendingUser();
		assertThrows(UsernameTakenException.class, () -> usernames.choose(newcomer, taken));
	}
}
