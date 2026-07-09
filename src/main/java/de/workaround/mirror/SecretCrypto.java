package de.workaround.mirror;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Symmetric encryption for secrets persisted at rest (mirror passwords/tokens, SSH private keys).
 * AES-256-GCM with the key derived from the configured {@code gitshark.secret-key}
 * ({@code GITSHARK_SECRET_KEY}) via SHA-256; every encryption uses a fresh random IV. Stored form:
 * {@code enc1:base64(iv || ciphertext)}. Without a configured key, encryption is unavailable and
 * features that need it must refuse to store secrets (fail closed, never fall back to plaintext).
 */
@Singleton
public class SecretCrypto
{
	private static final String PREFIX = "enc1:";

	private static final int IV_BYTES = 12;

	private static final int TAG_BITS = 128;

	// instance field, not static: a build-time-initialized static SecureRandom would land in the
	// native image heap with a cached seed, which GraalVM rejects (and would be unsafe anyway)
	private final SecureRandom random = new SecureRandom();

	private final SecretKeySpec key;

	@Inject
	SecretCrypto(@ConfigProperty(name = "gitshark.secret-key") Optional<String> configuredKey)
	{
		this(configuredKey.orElse(null));
	}

	private SecretCrypto(String rawKey)
	{
		this.key = rawKey == null || rawKey.isEmpty()
			? null
			: new SecretKeySpec(sha256(rawKey), "AES");
	}

	/** Test/factory entry point: a crypto bound to the given key material (null/empty = unavailable). */
	public static SecretCrypto forKey(String rawKey)
	{
		return new SecretCrypto(rawKey);
	}

	public boolean available()
	{
		return key != null;
	}

	public String encrypt(String plaintext)
	{
		requireKey();
		try
		{
			byte[] iv = new byte[IV_BYTES];
			random.nextBytes(iv);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
			byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			byte[] stored = new byte[iv.length + ciphertext.length];
			System.arraycopy(iv, 0, stored, 0, iv.length);
			System.arraycopy(ciphertext, 0, stored, iv.length, ciphertext.length);
			return PREFIX + Base64.getEncoder().encodeToString(stored);
		}
		catch (java.security.GeneralSecurityException e)
		{
			throw new IllegalStateException("Secret encryption failed", e);
		}
	}

	public String decrypt(String stored)
	{
		requireKey();
		if (!stored.startsWith(PREFIX))
		{
			throw new IllegalStateException("Not an encrypted secret (missing " + PREFIX + " prefix)");
		}
		byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
		try
		{
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES));
			byte[] plaintext = cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES);
			return new String(plaintext, StandardCharsets.UTF_8);
		}
		catch (java.security.GeneralSecurityException e)
		{
			throw new IllegalStateException("Secret decryption failed (wrong GITSHARK_SECRET_KEY?)", e);
		}
	}

	private void requireKey()
	{
		if (key == null)
		{
			throw new IllegalStateException(
				"No secret key configured: set GITSHARK_SECRET_KEY to store encrypted secrets");
		}
	}

	private static byte[] sha256(String value)
	{
		try
		{
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

}
