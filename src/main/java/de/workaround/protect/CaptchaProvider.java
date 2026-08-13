package de.workaround.protect;

import java.util.Locale;

/**
 * The supported bot-check widgets. Both Cloudflare Turnstile and hCaptcha follow the same shape —
 * a script tag, a div carrying the site key, a form field holding the solved token, and a
 * server-side {@code siteverify} POST — so only these four strings differ between them.
 */
public enum CaptchaProvider
{
	NONE("", "", "", ""),

	TURNSTILE(
		"https://challenges.cloudflare.com/turnstile/v0/api.js",
		"cf-turnstile",
		"cf-turnstile-response",
		"https://challenges.cloudflare.com/turnstile/v0/siteverify"),

	HCAPTCHA(
		"https://js.hcaptcha.com/1/api.js",
		"h-captcha",
		"h-captcha-response",
		"https://api.hcaptcha.com/siteverify");

	private final String scriptUrl;

	private final String widgetClass;

	private final String responseField;

	private final String defaultVerifyUrl;

	CaptchaProvider(String scriptUrl, String widgetClass, String responseField, String defaultVerifyUrl)
	{
		this.scriptUrl = scriptUrl;
		this.widgetClass = widgetClass;
		this.responseField = responseField;
		this.defaultVerifyUrl = defaultVerifyUrl;
	}

	/** Unknown or blank values fall back to {@link #NONE} — a typo must not silently disable metering. */
	public static CaptchaProvider parse(String value)
	{
		if (value == null || value.isBlank())
		{
			return NONE;
		}
		try
		{
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			return NONE;
		}
	}

	public String scriptUrl()
	{
		return scriptUrl;
	}

	public String widgetClass()
	{
		return widgetClass;
	}

	public String responseField()
	{
		return responseField;
	}

	public String defaultVerifyUrl()
	{
		return defaultVerifyUrl;
	}
}
