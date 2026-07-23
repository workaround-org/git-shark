package de.workaround.ci;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import de.workaround.model.CiRunner;
import de.workaround.model.CiRunnerRegistrationToken;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Registration and presence of CI/CD runners (Forgejo/Gitea runner.v1 protocol, phase 1).
 * <p>
 * Two secrets are involved, both stored only as SHA-256 hashes and shown in plaintext exactly once:
 * a shared <em>registration token</em> an admin generates, and a per-runner <em>secret</em> minted
 * during {@link #register}. Registration tokens are reusable (Gitea instance-token semantics).
 */
@ApplicationScoped
public class RunnerRegistrationService
{
	private static final String REGISTRATION_PREFIX = "gsr_";

	private static final String RUNNER_TOKEN_PREFIX = "gsrt_";

	@Inject
	CiRunner.Repo runners;

	@Inject
	CiRunnerRegistrationToken.Repo registrationTokens;

	public record CreatedRegistrationToken(CiRunnerRegistrationToken token, String plaintext)
	{
	}

	public record RegisteredRunner(CiRunner runner, String plaintext)
	{
	}

	@Transactional
	public CreatedRegistrationToken createRegistrationToken(User admin)
	{
		return createRegistrationToken(admin, null);
	}

	/** Create a registration token scoped to {@code repository} (null = instance-scope, any repository). */
	@Transactional
	public CreatedRegistrationToken createRegistrationToken(User admin, Repository repository)
	{
		String plaintext = REGISTRATION_PREFIX + randomSecret();
		CiRunnerRegistrationToken token = new CiRunnerRegistrationToken();
		token.tokenHash = hash(plaintext);
		token.createdBy = admin;
		token.repository = repository;
		token.persist();
		return new CreatedRegistrationToken(token, plaintext);
	}

	@Transactional
	public RegisteredRunner register(String registrationPlaintext, String name, List<String> labels, String version,
		boolean ephemeral)
	{
		CiRunnerRegistrationToken registration = registrationTokens.findByTokenHash(hash(registrationPlaintext))
			.orElseThrow(() -> new InvalidRegistrationTokenException("Unknown runner registration token"));
		registration.lastUsed = Instant.now();

		String secret = RUNNER_TOKEN_PREFIX + randomSecret();
		CiRunner runner = new CiRunner();
		runner.uuid = UUID.randomUUID().toString();
		runner.tokenHash = hash(secret);
		runner.name = name;
		runner.labels = joinLabels(labels);
		runner.version = version;
		runner.ephemeral = ephemeral;
		runner.repository = registration.repository;
		runner.status = CiRunner.Status.IDLE;
		runner.lastSeen = Instant.now();
		runner.persist();
		return new RegisteredRunner(runner, secret);
	}

	/**
	 * Authenticate a runner by its uuid + secret and refresh its declared version/labels. Called for
	 * the Declare RPC (and, going forward, every authenticated post-registration call).
	 */
	@Transactional
	public CiRunner declare(String uuid, String tokenPlaintext, String version, List<String> labels)
	{
		CiRunner runner = authenticate(uuid, tokenPlaintext);
		runner.version = version;
		runner.labels = joinLabels(labels);
		runner.status = CiRunner.Status.IDLE;
		runner.lastSeen = Instant.now();
		return runner;
	}

	/** Resolve a runner from its credentials or throw {@link RunnerAuthenticationException}. */
	@Transactional
	public CiRunner authenticate(String uuid, String tokenPlaintext)
	{
		if (uuid == null || tokenPlaintext == null)
		{
			throw new RunnerAuthenticationException("Missing runner credentials");
		}
		return runners.findByUuidAndTokenHash(uuid, hash(tokenPlaintext))
			.orElseThrow(() -> new RunnerAuthenticationException("Unknown runner uuid/token"));
	}

	public List<CiRunner> list()
	{
		return runners.listNewestFirst();
	}

	public List<CiRunnerRegistrationToken> listRegistrationTokens()
	{
		return registrationTokens.listNewestFirst();
	}

	@Transactional
	public void delete(UUID id)
	{
		runners.deleteById(id);
	}

	@Transactional
	public void deleteRegistrationToken(UUID id)
	{
		registrationTokens.deleteById(id);
	}

	private static String joinLabels(List<String> labels)
	{
		return labels == null ? "" : String.join(",", labels);
	}

	private static String randomSecret()
	{
		byte[] bytes = new byte[32];
		// created per call: a SecureRandom held in a bean field would be snapshotted into the native
		// image heap, which GraalVM rejects (same rationale as AccessTokenService)
		new SecureRandom().nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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
