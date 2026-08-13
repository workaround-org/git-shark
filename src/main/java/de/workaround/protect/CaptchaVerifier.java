package de.workaround.protect;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Server-side token check against the provider's {@code siteverify} endpoint. Turnstile and hCaptcha
 * share the request contract ({@code secret}, {@code response}, optional {@code remoteip}) and the
 * {@code {"success": bool}} reply, so one implementation serves both.
 *
 * <p>Anything unexpected — timeout, non-200, unparseable body — counts as "not verified": a captcha
 * that cannot be checked must never grant a pass.
 */
@ApplicationScoped
public class CaptchaVerifier
{
	private static final Logger LOG = Logger.getLogger(CaptchaVerifier.class.getName());

	// instance field, not static: a build-time-initialized client would be baked into the native image
	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(5))
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();

	@Inject
	ProtectionConfig config;

	@Inject
	ObjectMapper mapper;

	public boolean verify(String token, String remoteIp)
	{
		if (token == null || token.isBlank())
		{
			return false;
		}
		Optional<String> secret = config.secretKey();
		Optional<String> endpoint = config.verifyUrl();
		if (secret.isEmpty() || endpoint.isEmpty())
		{
			return false;
		}
		try
		{
			HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint.get()))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(form(secret.get(), token, remoteIp)))
				.build();
			HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200)
			{
				LOG.log(Level.WARNING, "captcha siteverify returned HTTP {0}", response.statusCode());
				return false;
			}
			JsonNode body = mapper.readTree(response.body());
			return body.path("success").asBoolean(false);
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
		catch (Exception e)
		{
			LOG.log(Level.WARNING, "captcha siteverify failed: " + e.getMessage(), e);
			return false;
		}
	}

	private static String form(String secret, String token, String remoteIp)
	{
		StringBuilder body = new StringBuilder()
			.append("secret=").append(encode(secret))
			.append("&response=").append(encode(token));
		if (remoteIp != null && !remoteIp.isBlank() && !remoteIp.equals("unknown"))
		{
			body.append("&remoteip=").append(encode(remoteIp));
		}
		return body.toString();
	}

	private static String encode(String value)
	{
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
