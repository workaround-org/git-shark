package de.workaround.protect;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.function.LongSupplier;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The "this visitor already solved a captcha" pass: {@code <expiry-epoch-seconds>.<hmac>}, carried in
 * a cookie. Self-contained and self-expiring, so no server-side session table is needed and the
 * value survives a restart or a hop to another pod.
 *
 * <p>The HMAC key is derived from the captcha secret key — the one secret that is necessarily present
 * whenever challenges can be issued at all — so no extra configuration is required. Without it, no
 * pass can be minted or accepted (fail closed).
 */
@ApplicationScoped
public class HumanPass
{
	public static final String COOKIE_NAME = "gitshark_human";

	private static final String ALGORITHM = "HmacSHA256";

	private final SecretKeySpec key;

	private final Duration duration;

	private final LongSupplier clock;

	@Inject
	HumanPass(ProtectionConfig config)
	{
		this(config.secretKey().orElse(null), config.passDuration(), System::currentTimeMillis);
	}

	HumanPass(String secret, Duration duration, LongSupplier clock)
	{
		this.key = secret == null || secret.isBlank() ? null : new SecretKeySpec(sha256(secret), ALGORITHM);
		this.duration = duration;
		this.clock = clock;
	}

	public boolean available()
	{
		return key != null;
	}

	/** Mints a pass valid for the configured duration. */
	public String issue()
	{
		if (key == null)
		{
			throw new IllegalStateException("no captcha secret key configured — cannot issue a human pass");
		}
		long expiry = clock.getAsLong() / 1000 + Math.max(1, duration.toSeconds());
		return expiry + "." + sign(expiry);
	}

	/** True for an unexpired value signed by this instance. */
	public boolean valid(String value)
	{
		if (key == null || value == null)
		{
			return false;
		}
		int separator = value.lastIndexOf('.');
		if (separator <= 0 || separator == value.length() - 1)
		{
			return false;
		}
		long expiry;
		try
		{
			expiry = Long.parseLong(value.substring(0, separator));
		}
		catch (NumberFormatException e)
		{
			return false;
		}
		if (expiry <= clock.getAsLong() / 1000)
		{
			return false;
		}
		byte[] presented = value.substring(separator + 1).getBytes(StandardCharsets.UTF_8);
		byte[] expected = sign(expiry).getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(presented, expected);
	}

	/** Cookie lifetime in seconds, matching the pass's own expiry. */
	public int cookieMaxAge()
	{
		return (int) Math.max(1, duration.toSeconds());
	}

	private String sign(long expiry)
	{
		try
		{
			Mac mac = Mac.getInstance(ALGORITHM);
			mac.init(key);
			byte[] signature = mac.doFinal(("gitshark-human:" + expiry).getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
		}
		catch (NoSuchAlgorithmException | InvalidKeyException e)
		{
			throw new IllegalStateException("HMAC-SHA256 unavailable", e);
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
