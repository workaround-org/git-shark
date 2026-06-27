package de.workaround.account;

/**
 * Derives a URL-safe handle suggestion from an OIDC {@code preferred_username} claim. With kanidm the
 * claim is the SPN form {@code user@domain}; the suggestion strips the domain, lowercases, replaces
 * characters outside the handle charset with {@code -}, trims separators, and clamps to the handle
 * length. The result is only a pre-fill; the user may change it on the onboarding page.
 */
public final class HandleSuggester
{
	private static final int MAX_LENGTH = 39;

	private HandleSuggester()
	{
	}

	public static String suggest(String claim)
	{
		if (claim == null || claim.isBlank())
		{
			return "";
		}
		String local = claim.split("@", 2)[0];
		String sanitized = local.toLowerCase()
			.replaceAll("[^a-z0-9-]", "-")
			.replaceAll("-{2,}", "-")
			.replaceAll("^-+", "")
			.replaceAll("-+$", "");
		if (sanitized.length() > MAX_LENGTH)
		{
			sanitized = sanitized.substring(0, MAX_LENGTH).replaceAll("-+$", "");
		}
		return sanitized;
	}
}
