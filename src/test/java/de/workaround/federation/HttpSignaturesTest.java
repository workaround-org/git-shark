package de.workaround.federation;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure crypto tests for the draft-cavage HTTP Signature sign/verify round-trip. */
class HttpSignaturesTest
{
	private static final URI INBOX = URI.create("https://peer.test/ap/repos/bob/lib/inbox");

	private static final String KEY_ID = "https://shark.test/ap/repos/alice/app#main-key";

	private final KeyPair keyPair = rsaKeyPair();

	@Test
	void signedRequestVerifies()
	{
		byte[] body = "{\"type\":\"Follow\",\"id\":\"https://shark.test/a/1\"}".getBytes(StandardCharsets.UTF_8);
		Map<String, String> headers = HttpSignatures.signPost(INBOX, body, KEY_ID, keyPair.getPrivate());

		HttpSignatures.SignatureHeader parsed = HttpSignatures.parse(headers.get("Signature"));
		assertNotNull(parsed);
		assertTrue(HttpSignatures.verify(parsed, "POST", INBOX.getRawPath(), lookup(headers), body,
			keyPair.getPublic()));
	}

	@Test
	void tamperedBodyFailsVerification()
	{
		byte[] body = "{\"id\":\"https://shark.test/a/2\"}".getBytes(StandardCharsets.UTF_8);
		Map<String, String> headers = HttpSignatures.signPost(INBOX, body, KEY_ID, keyPair.getPrivate());
		HttpSignatures.SignatureHeader parsed = HttpSignatures.parse(headers.get("Signature"));

		byte[] tampered = "{\"id\":\"https://shark.test/a/EVIL\"}".getBytes(StandardCharsets.UTF_8);
		assertFalse(HttpSignatures.verify(parsed, "POST", INBOX.getRawPath(), lookup(headers), tampered,
			keyPair.getPublic()));
	}

	@Test
	void wrongKeyFailsVerification()
	{
		byte[] body = "{\"id\":\"https://shark.test/a/3\"}".getBytes(StandardCharsets.UTF_8);
		Map<String, String> headers = HttpSignatures.signPost(INBOX, body, KEY_ID, keyPair.getPrivate());
		HttpSignatures.SignatureHeader parsed = HttpSignatures.parse(headers.get("Signature"));

		assertFalse(HttpSignatures.verify(parsed, "POST", INBOX.getRawPath(), lookup(headers), body,
			rsaKeyPair().getPublic()));
	}

	@Test
	void staleDateFailsVerification()
	{
		byte[] body = "{\"id\":\"https://shark.test/a/4\"}".getBytes(StandardCharsets.UTF_8);
		Map<String, String> headers = HttpSignatures.signPost(INBOX, body, KEY_ID, keyPair.getPrivate());
		HttpSignatures.SignatureHeader parsed = HttpSignatures.parse(headers.get("Signature"));

		Map<String, String> stale = new HashMap<>(headers);
		stale.put("Date", DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
			.format(ZonedDateTime.now(ZoneOffset.UTC).minusHours(2)));
		// digest still matches, but the date is outside the allowed skew
		assertFalse(HttpSignatures.verify(parsed, "POST", INBOX.getRawPath(), lookup(stale), body,
			keyPair.getPublic()));
	}

	@Test
	void missingSignatureHeaderParsesToNull()
	{
		assertFalse(HttpSignatures.parse(null) != null);
		assertFalse(HttpSignatures.parse("garbage-without-keyid") != null);
	}

	private static Function<String, String> lookup(Map<String, String> headers)
	{
		Map<String, String> lower = new HashMap<>();
		headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
		return name -> lower.get(name.toLowerCase(Locale.ROOT));
	}

	private static KeyPair rsaKeyPair()
	{
		try
		{
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

}
