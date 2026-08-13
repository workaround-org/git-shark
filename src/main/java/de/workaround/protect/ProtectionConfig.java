package de.workaround.protect;

import java.time.Duration;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Configuration for the abuse guard on expensive renderings (commit and diff views, search). Two
 * budgets per window — one for anonymous callers keyed by client IP, one for logged-in users keyed
 * by their account — plus an optional captcha that lets a refused human continue.
 *
 * <p>The captcha is opt-in and fails <em>open</em> only in the sense that no captcha means no
 * challenge page; the rate limit itself still applies and refusals become plain 429s. An
 * incompletely configured captcha (provider set, keys missing) counts as no captcha.
 */
@ApplicationScoped
public class ProtectionConfig
{
	@ConfigProperty(name = "gitshark.protect.enabled", defaultValue = "true")
	boolean enabled;

	@ConfigProperty(name = "gitshark.protect.anonymous-limit", defaultValue = "30")
	int anonymousLimit;

	@ConfigProperty(name = "gitshark.protect.user-limit", defaultValue = "120")
	int userLimit;

	@ConfigProperty(name = "gitshark.protect.window", defaultValue = "1m")
	Duration window;

	@ConfigProperty(name = "gitshark.protect.captcha.provider", defaultValue = "none")
	String provider;

	@ConfigProperty(name = "gitshark.protect.captcha.site-key")
	Optional<String> siteKey;

	@ConfigProperty(name = "gitshark.protect.captcha.secret-key")
	Optional<String> secretKey;

	@ConfigProperty(name = "gitshark.protect.captcha.verify-url")
	Optional<String> verifyUrl;

	@ConfigProperty(name = "gitshark.protect.captcha.pass-duration", defaultValue = "15m")
	Duration passDuration;

	public boolean enabled()
	{
		return enabled;
	}

	public int anonymousLimit()
	{
		return anonymousLimit;
	}

	public int userLimit()
	{
		return userLimit;
	}

	public Duration window()
	{
		return window;
	}

	public CaptchaProvider provider()
	{
		return CaptchaProvider.parse(provider);
	}

	public Optional<String> siteKey()
	{
		return nonBlank(siteKey);
	}

	public Optional<String> secretKey()
	{
		return nonBlank(secretKey);
	}

	/** The configured override, or the provider's own endpoint. Empty when no provider is selected. */
	public Optional<String> verifyUrl()
	{
		Optional<String> configured = nonBlank(verifyUrl);
		if (configured.isPresent())
		{
			return configured;
		}
		CaptchaProvider selected = provider();
		return selected == CaptchaProvider.NONE ? Optional.empty() : Optional.of(selected.defaultVerifyUrl());
	}

	/** How long a solved challenge keeps a visitor on the user budget instead of the anonymous one. */
	public Duration passDuration()
	{
		return passDuration;
	}

	/** True only when a provider is selected AND both of its keys are present. */
	public boolean captchaConfigured()
	{
		return provider() != CaptchaProvider.NONE && siteKey().isPresent() && secretKey().isPresent();
	}

	private static Optional<String> nonBlank(Optional<String> value)
	{
		return value.map(String::trim).filter(trimmed -> !trimmed.isEmpty());
	}
}
