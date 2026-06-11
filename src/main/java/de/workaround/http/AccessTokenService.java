package de.workaround.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.workaround.git.ForbiddenOperationException;
import de.workaround.model.AccessToken;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Personal access tokens for Git-over-HTTP Basic authentication. The plaintext token is
 * returned exactly once at creation; only its SHA-256 hash is persisted.
 */
@ApplicationScoped
public class AccessTokenService
{
	private static final String PREFIX = "gs_";

	@Inject
	AccessToken.Repo tokens;

	public record CreatedToken(AccessToken token, String plaintext)
	{
	}

	@Transactional
	public CreatedToken create(User user, String label)
	{
		byte[] bytes = new byte[32];
		// created per call: a SecureRandom held in a bean field would be snapshotted
		// into the native image heap, which GraalVM rejects
		new SecureRandom().nextBytes(bytes);
		String plaintext = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

		AccessToken token = new AccessToken();
		token.user = user;
		token.label = label;
		token.tokenHash = hash(plaintext);
		token.persist();
		return new CreatedToken(token, plaintext);
	}

	@Transactional
	public Optional<User> authenticate(String plaintext)
	{
		if (plaintext == null || plaintext.isEmpty())
		{
			return Optional.empty();
		}
		return tokens.findByTokenHash(hash(plaintext)).map(token -> {
			token.lastUsed = Instant.now();
			return token.user;
		});
	}

	@Transactional
	public void revoke(User actor, UUID tokenId)
	{
		AccessToken token = tokens.findById(tokenId);
		if (token == null)
		{
			return;
		}
		if (!token.user.id.equals(actor.id))
		{
			throw new ForbiddenOperationException("Only the owner may revoke a token");
		}
		tokens.deleteById(tokenId);
	}

	public List<AccessToken> list(User user)
	{
		return tokens.findByUser(user);
	}

	public String hash(String plaintext)
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(plaintext.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

}
