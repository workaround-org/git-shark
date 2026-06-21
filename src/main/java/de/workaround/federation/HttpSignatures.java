package de.workaround.federation;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * HTTP Signatures (draft-cavage, {@code rsa-sha256}) for ActivityPub server-to-server auth.
 * Signs over {@code (request-target) host date digest}; verification rebuilds the same signing
 * string from the actual request, checks the RSA signature, the body {@code Digest}, and the
 * {@code Date} skew. Fails closed.
 */
public final class HttpSignatures
{
	private static final DateTimeFormatter HTTP_DATE =
		DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH);

	private static final Duration MAX_SKEW = Duration.ofMinutes(5);

	private static final List<String> SIGNED_HEADERS = List.of("(request-target)", "host", "date", "digest");

	private HttpSignatures()
	{
	}

	/** Parsed {@code Signature} header. */
	public record SignatureHeader(String keyId, String algorithm, List<String> headers, byte[] signature)
	{
	}

	/**
	 * Builds the headers a signed POST must carry: {@code Host}, {@code Date}, {@code Digest}, and
	 * {@code Signature}. The caller sets them on the outgoing request.
	 */
	public static Map<String, String> signPost(URI target, byte[] body, String keyId, PrivateKey key)
	{
		String host = target.getHost() + (target.getPort() > 0 ? ":" + target.getPort() : "");
		String date = HTTP_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
		String digest = "SHA-256=" + Base64.getEncoder().encodeToString(sha256(body));

		Map<String, String> headerValues = new LinkedHashMap<>();
		headerValues.put("(request-target)", "post " + target.getRawPath());
		headerValues.put("host", host);
		headerValues.put("date", date);
		headerValues.put("digest", digest);

		String signingString = signingString(SIGNED_HEADERS, headerValues::get);
		String signature = Base64.getEncoder().encodeToString(rsaSign(signingString, key));

		String signatureHeader = "keyId=\"" + keyId + "\",algorithm=\"rsa-sha256\",headers=\""
			+ String.join(" ", SIGNED_HEADERS) + "\",signature=\"" + signature + "\"";

		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("Host", host);
		headers.put("Date", date);
		headers.put("Digest", digest);
		headers.put("Signature", signatureHeader);
		return headers;
	}

	public static SignatureHeader parse(String signatureHeader)
	{
		if (signatureHeader == null)
		{
			return null;
		}
		Map<String, String> params = new LinkedHashMap<>();
		for (String part : splitTopLevel(signatureHeader))
		{
			int eq = part.indexOf('=');
			if (eq < 0)
			{
				continue;
			}
			String name = part.substring(0, eq).trim();
			String value = part.substring(eq + 1).trim();
			if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
			{
				value = value.substring(1, value.length() - 1);
			}
			params.put(name, value);
		}
		String keyId = params.get("keyId");
		String signature = params.get("signature");
		String headers = params.getOrDefault("headers", "(request-target) host date digest");
		if (keyId == null || signature == null)
		{
			return null;
		}
		return new SignatureHeader(keyId, params.getOrDefault("algorithm", "rsa-sha256"),
			List.of(headers.split(" ")), Base64.getDecoder().decode(signature));
	}

	/**
	 * Verifies the parsed signature against the request. {@code headerLookup} resolves a header
	 * name (lowercased) to its value; {@code (request-target)} is synthesized from method + path.
	 */
	public static boolean verify(SignatureHeader header, String method, String path,
		Function<String, String> headerLookup, byte[] body, PublicKey key)
	{
		if (header == null)
		{
			return false;
		}
		if (!checkDigest(headerLookup.apply("digest"), body) || !checkDate(headerLookup.apply("date")))
		{
			return false;
		}
		String requestTarget = method.toLowerCase(Locale.ROOT) + " " + path;
		String signingString = signingString(header.headers(), name ->
			name.equals("(request-target)") ? requestTarget : headerLookup.apply(name));
		try
		{
			Signature verifier = Signature.getInstance("SHA256withRSA");
			verifier.initVerify(key);
			verifier.update(signingString.getBytes(StandardCharsets.UTF_8));
			return verifier.verify(header.signature());
		}
		catch (GeneralSecurityException e)
		{
			return false;
		}
	}

	private static boolean checkDigest(String digestHeader, byte[] body)
	{
		if (digestHeader == null || !digestHeader.regionMatches(true, 0, "SHA-256=", 0, 8))
		{
			return false;
		}
		String expected = Base64.getEncoder().encodeToString(sha256(body));
		return constantTimeEquals(digestHeader.substring(8), expected);
	}

	private static boolean checkDate(String dateHeader)
	{
		if (dateHeader == null)
		{
			return false;
		}
		try
		{
			ZonedDateTime date = ZonedDateTime.parse(dateHeader, HTTP_DATE.withZone(ZoneOffset.UTC));
			Duration delta = Duration.between(date, ZonedDateTime.now(ZoneOffset.UTC)).abs();
			return delta.compareTo(MAX_SKEW) <= 0;
		}
		catch (RuntimeException e)
		{
			return false;
		}
	}

	private static String signingString(List<String> headers, Function<String, String> lookup)
	{
		List<String> lines = new ArrayList<>();
		for (String name : headers)
		{
			String value = lookup.apply(name.toLowerCase(Locale.ROOT));
			lines.add(name.toLowerCase(Locale.ROOT) + ": " + (value == null ? "" : value));
		}
		return String.join("\n", lines);
	}

	private static byte[] rsaSign(String data, PrivateKey key)
	{
		try
		{
			Signature signer = Signature.getInstance("SHA256withRSA");
			signer.initSign(key);
			signer.update(data.getBytes(StandardCharsets.UTF_8));
			return signer.sign();
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException("Signing failed", e);
		}
	}

	private static byte[] sha256(byte[] body)
	{
		try
		{
			return MessageDigest.getInstance("SHA-256").digest(body);
		}
		catch (GeneralSecurityException e)
		{
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static List<String> splitTopLevel(String header)
	{
		List<String> parts = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean inQuotes = false;
		for (char c : header.toCharArray())
		{
			if (c == '"')
			{
				inQuotes = !inQuotes;
			}
			if (c == ',' && !inQuotes)
			{
				parts.add(current.toString());
				current.setLength(0);
			}
			else
			{
				current.append(c);
			}
		}
		if (current.length() > 0)
		{
			parts.add(current.toString());
		}
		return parts;
	}

	private static boolean constantTimeEquals(String a, String b)
	{
		return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
	}

}
