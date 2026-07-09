package de.workaround.mirror;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mirror secrets (HTTPS tokens, SSH private keys) are encrypted at rest with a symmetric key from
 * configuration. The ciphertext must never equal or contain the plaintext, and every encryption
 * must use a fresh IV so identical secrets do not produce identical ciphertexts.
 */
class SecretCryptoTest
{
	@Test
	void roundTripsPlaintext()
	{
		SecretCrypto crypto = SecretCrypto.forKey("unit-test-key");
		String stored = crypto.encrypt("hunter2-token");
		assertEquals("hunter2-token", crypto.decrypt(stored));
	}

	@Test
	void ciphertextDoesNotContainPlaintext()
	{
		SecretCrypto crypto = SecretCrypto.forKey("unit-test-key");
		String stored = crypto.encrypt("hunter2-token");
		assertNotEquals("hunter2-token", stored);
		assertFalse(stored.contains("hunter2-token"));
	}

	@Test
	void freshIvPerEncryption()
	{
		SecretCrypto crypto = SecretCrypto.forKey("unit-test-key");
		assertNotEquals(crypto.encrypt("same-secret"), crypto.encrypt("same-secret"));
	}

	@Test
	void decryptWithWrongKeyFails()
	{
		String stored = SecretCrypto.forKey("key-a").encrypt("secret");
		assertThrows(IllegalStateException.class, () -> SecretCrypto.forKey("key-b").decrypt(stored));
	}

	@Test
	void unavailableWithoutConfiguredKey()
	{
		assertFalse(SecretCrypto.forKey(null).available());
		assertFalse(SecretCrypto.forKey("").available());
		assertTrue(SecretCrypto.forKey("k").available());
	}

}
