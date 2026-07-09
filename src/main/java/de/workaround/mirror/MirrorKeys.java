package de.workaround.mirror;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.util.PrivateKeyFactory;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.bouncycastle.jce.provider.BouncyCastleProvider;

/**
 * Ed25519 deploy keys for SSH mirrors, generated via BouncyCastle (registered for the SSH server
 * anyway, and reliable in native images). The private key is stored as PKCS#8 PEM (encrypted at
 * rest); the public key derives from it, so the PEM alone reconstructs the full keypair.
 */
public final class MirrorKeys
{
	static
	{
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null)
		{
			Security.addProvider(new BouncyCastleProvider());
		}
	}

	private MirrorKeys()
	{
	}

	public static KeyPair generateEd25519()
	{
		try
		{
			return KeyPairGenerator.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME).generateKeyPair();
		}
		catch (java.security.GeneralSecurityException e)
		{
			throw new IllegalStateException("Ed25519 key generation unavailable", e);
		}
	}

	/** Renders an authorized_keys line: {@code ssh-ed25519 <base64 wire blob> git-shark-mirror}. */
	public static String sshPublicKey(PublicKey publicKey)
	{
		// The raw 32-byte point is the tail of the X.509 SubjectPublicKeyInfo (fixed layout for Ed25519).
		byte[] spki = publicKey.getEncoded();
		byte[] raw = Arrays.copyOfRange(spki, spki.length - 32, spki.length);
		byte[] algorithm = "ssh-ed25519".getBytes(StandardCharsets.US_ASCII);
		byte[] wire = new byte[4 + algorithm.length + 4 + raw.length];
		writeLength(wire, 0, algorithm.length);
		System.arraycopy(algorithm, 0, wire, 4, algorithm.length);
		writeLength(wire, 4 + algorithm.length, raw.length);
		System.arraycopy(raw, 0, wire, 8 + algorithm.length, raw.length);
		return "ssh-ed25519 " + Base64.getEncoder().encodeToString(wire) + " git-shark-mirror";
	}

	public static String privatePem(PrivateKey privateKey)
	{
		String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
			.encodeToString(privateKey.getEncoded());
		return "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n";
	}

	/** Reconstructs the full keypair from the stored PKCS#8 PEM (the public key derives from the private). */
	public static KeyPair fromPrivatePem(String pem)
	{
		byte[] der = decodePem(pem);
		try
		{
			KeyFactory factory = KeyFactory.getInstance("Ed25519", BouncyCastleProvider.PROVIDER_NAME);
			PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(der));
			Ed25519PrivateKeyParameters parameters = (Ed25519PrivateKeyParameters) PrivateKeyFactory.createKey(der);
			Ed25519PublicKeyParameters publicParameters = parameters.generatePublicKey();
			PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
				SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(publicParameters).getEncoded()));
			return new KeyPair(publicKey, privateKey);
		}
		catch (java.security.GeneralSecurityException | IOException e)
		{
			throw new IllegalArgumentException("Invalid Ed25519 private key PEM", e);
		}
	}

	private static void writeLength(byte[] target, int offset, int length)
	{
		target[offset] = (byte) (length >>> 24);
		target[offset + 1] = (byte) (length >>> 16);
		target[offset + 2] = (byte) (length >>> 8);
		target[offset + 3] = (byte) length;
	}

	private static byte[] decodePem(String pem)
	{
		String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
			.replaceAll("-----END [A-Z ]+-----", "")
			.replaceAll("\\s", "");
		return Base64.getDecoder().decode(base64);
	}

}
