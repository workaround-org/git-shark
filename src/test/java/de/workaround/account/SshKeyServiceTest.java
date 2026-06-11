package de.workaround.account;

import java.security.KeyPairGenerator;
import java.util.UUID;

import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.junit.jupiter.api.Test;

import de.workaround.model.SshKey;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SshKeyServiceTest
{
	@Inject
	SshKeyService service;

	@Inject
	SshKey.Repo sshKeys;

	@Test
	@TestTransaction
	void addsValidOpenSshKeyAndComputesFingerprint()
	{
		User user = persistUser();

		SshKey key = service.add(user, "laptop", validOpenSshKey());

		assertTrue(key.fingerprint.startsWith("SHA256:"), "fingerprint computed in OpenSSH format");
		assertEquals(1, service.list(user).size());
	}

	@Test
	@TestTransaction
	void rejectsMalformedKeyMaterial()
	{
		User user = persistUser();

		assertThrows(InvalidSshKeyException.class, () -> service.add(user, "bad", "not-a-key AAAA"));
		assertThrows(InvalidSshKeyException.class, () -> service.add(user, "empty", ""));
	}

	@Test
	@TestTransaction
	void rejectsKeyAlreadyRegisteredToAnyAccount()
	{
		User first = persistUser();
		User second = persistUser();
		String key = validOpenSshKey();
		service.add(first, "original", key);

		assertThrows(DuplicateSshKeyException.class, () -> service.add(second, "copy", key));
	}

	@Test
	@TestTransaction
	void removedKeyIsGoneFromLookup()
	{
		User user = persistUser();
		SshKey key = service.add(user, "transient", validOpenSshKey());
		String fingerprint = key.fingerprint;

		service.remove(user, key.id);

		assertTrue(sshKeys.findByFingerprint(fingerprint).isEmpty(),
			"SSH authenticator lookup must no longer find the key");
	}

	@Test
	@TestTransaction
	void removeByNonOwnerForbidden()
	{
		User owner = persistUser();
		User stranger = persistUser();
		SshKey key = service.add(owner, "mine", validOpenSshKey());

		assertThrows(de.workaround.git.ForbiddenOperationException.class, () -> service.remove(stranger, key.id));
	}

	private static String validOpenSshKey()
	{
		try
		{
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return PublicKeyEntry.toString(generator.generateKeyPair().getPublic()) + " test@example.com";
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static User persistUser()
	{
		String name = "key-" + UUID.randomUUID();
		User user = new User();
		user.oidcSub = "sub-" + name;
		user.username = name;
		user.persist();
		return user;
	}

}
