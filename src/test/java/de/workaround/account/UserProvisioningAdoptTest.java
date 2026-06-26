package de.workaround.account;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dev-only adoption: when the OIDC subject is unknown but a user with the same username already
 * exists (e.g. a seeded dev user), provisioning adopts that row instead of creating a duplicate
 * (which the unique username constraint would reject).
 */
@QuarkusTest
@TestProfile(UserProvisioningAdoptTest.AdoptProfile.class)
class UserProvisioningAdoptTest
{
	public static class AdoptProfile implements QuarkusTestProfile
	{
		@Override
		public Map<String, String> getConfigOverrides()
		{
			return Map.of("gitshark.dev.adopt-username", "true");
		}
	}

	@Inject
	UserProvisioningService service;

	@Inject
	User.Repo users;

	@Test
	@TestTransaction
	void adoptsExistingUsernameWhenSubjectUnknown()
	{
		String username = "adopt-" + UUID.randomUUID();
		User seeded = new User();
		seeded.oidcSub = "seed:" + username;
		seeded.username = username;
		seeded.persist();

		String loginSub = "kc:" + UUID.randomUUID();
		User logged = service.provision(loginSub, username, "Adopted", "adopt@example.com");

		assertEquals(seeded.id, logged.id, "must adopt the existing row, not create a duplicate");
		assertEquals(loginSub, users.findById(seeded.id).oidcSub, "adopted row takes the login subject");
		assertEquals("Adopted", logged.displayName);
	}

}
