package de.workaround.mirror;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Each SSH mirror gets a server-generated Ed25519 deploy keypair. The public key must be rendered
 * in OpenSSH authorized_keys format (so it can be pasted as a deploy key), and the stored private
 * key PEM must reconstruct the full keypair for the JGit SSH client.
 */
class MirrorKeysTest
{
	@Test
	void generatesEd25519KeyPair()
	{
		KeyPair pair = MirrorKeys.generateEd25519();
		assertEquals("Ed25519", pair.getPrivate().getAlgorithm().replace("EdDSA", "Ed25519"));
	}

	@Test
	void publicKeyIsOpenSshFormat() throws Exception
	{
		KeyPair pair = MirrorKeys.generateEd25519();
		String rendered = MirrorKeys.sshPublicKey(pair.getPublic());
		assertTrue(rendered.startsWith("ssh-ed25519 "), "must be an authorized_keys line: " + rendered);

		// wire blob: string("ssh-ed25519") + string(32-byte raw point), per RFC 4253/8709
		String blob = rendered.split(" ")[1];
		byte[] decoded = Base64.getDecoder().decode(blob);
		assertEquals(4 + 11 + 4 + 32, decoded.length);
		assertEquals("ssh-ed25519", new String(decoded, 4, 11, java.nio.charset.StandardCharsets.US_ASCII));
		byte[] wireRaw = java.util.Arrays.copyOfRange(decoded, decoded.length - 32, decoded.length);
		assertArrayEquals(rawEd25519(pair.getPublic()), wireRaw);
	}

	@Test
	void privatePemRoundTripsToSameKeyPair()
	{
		KeyPair pair = MirrorKeys.generateEd25519();
		String pem = MirrorKeys.privatePem(pair.getPrivate());
		assertTrue(pem.contains("BEGIN PRIVATE KEY"));

		KeyPair restored = MirrorKeys.fromPrivatePem(pem);
		assertEquals(MirrorKeys.sshPublicKey(pair.getPublic()), MirrorKeys.sshPublicKey(restored.getPublic()));
		assertArrayEquals(pair.getPrivate().getEncoded(), restored.getPrivate().getEncoded());
	}

	@Test
	void everyMirrorGetsItsOwnKey()
	{
		assertNotEquals(MirrorKeys.sshPublicKey(MirrorKeys.generateEd25519().getPublic()),
			MirrorKeys.sshPublicKey(MirrorKeys.generateEd25519().getPublic()));
	}

	/** Last 32 bytes of the X.509 SubjectPublicKeyInfo are the raw Ed25519 point. */
	private static byte[] rawEd25519(PublicKey key)
	{
		byte[] spki = key.getEncoded();
		return java.util.Arrays.copyOfRange(spki, spki.length - 32, spki.length);
	}

}
