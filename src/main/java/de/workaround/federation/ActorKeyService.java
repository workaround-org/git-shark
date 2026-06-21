package de.workaround.federation;

import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import de.workaround.model.FederationKey;
import de.workaround.model.FederationKey.ActorType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Generates and persists one RSA-2048 keypair per local federation actor (repository, user,
 * instance) and parses PEM keys. RSA is used for maximum ActivityPub interop. Keys are created
 * lazily on first federation use and reused across restarts.
 */
@ApplicationScoped
public class ActorKeyService
{
	@Inject
	FederationKey.Repo keys;

	/** Returns the actor's keypair record, generating and persisting it on first use. */
	@Transactional
	public synchronized FederationKey getOrCreate(ActorType type, String ref)
	{
		return keys.findByActorTypeAndActorRef(type, ref).orElseGet(() -> generate(type, ref));
	}

	private FederationKey generate(ActorType type, String ref)
	{
		KeyPair pair = generateKeyPair();
		FederationKey key = new FederationKey();
		key.actorType = type;
		key.actorRef = ref;
		key.publicPem = toPem("PUBLIC KEY", pair.getPublic().getEncoded());
		key.privatePem = toPem("PRIVATE KEY", pair.getPrivate().getEncoded());
		key.persist();
		return key;
	}

	private static KeyPair generateKeyPair()
	{
		try
		{
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("RSA key generation unavailable", e);
		}
	}

	public static PrivateKey parsePrivate(String pem)
	{
		try
		{
			return KeyFactory.getInstance("RSA")
				.generatePrivate(new PKCS8EncodedKeySpec(decode(pem)));
		}
		catch (NoSuchAlgorithmException | InvalidKeySpecException e)
		{
			throw new IllegalArgumentException("Invalid private key PEM", e);
		}
	}

	public static PublicKey parsePublic(String pem)
	{
		try
		{
			return KeyFactory.getInstance("RSA")
				.generatePublic(new X509EncodedKeySpec(decode(pem)));
		}
		catch (NoSuchAlgorithmException | InvalidKeySpecException e)
		{
			throw new IllegalArgumentException("Invalid public key PEM", e);
		}
	}

	private static String toPem(String label, byte[] der)
	{
		String body = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
		return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
	}

	private static byte[] decode(String pem)
	{
		String base64 = pem.replaceAll("-----BEGIN [A-Z ]+-----", "")
			.replaceAll("-----END [A-Z ]+-----", "")
			.replaceAll("\\s", "");
		return Base64.getDecoder().decode(base64);
	}

}
