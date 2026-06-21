package de.workaround.federation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Central federation switch and validated configuration. Federation is disabled by default;
 * actor IDs are absolute and permanent once published, so emission is only "operational" when a
 * valid, non-loopback {@code base-url} is configured (fail closed). The peer allowlist bounds the
 * first git-shark&#8596;git-shark rollout — an empty allowlist denies every remote peer.
 */
@ApplicationScoped
public class FederationConfig
{
	@ConfigProperty(name = "gitshark.federation.enabled", defaultValue = "false")
	boolean enabled;

	@ConfigProperty(name = "gitshark.federation.base-url")
	Optional<String> baseUrl;

	@ConfigProperty(name = "gitshark.federation.peer-allowlist")
	Optional<List<String>> peerAllowlist;

	@ConfigProperty(name = "gitshark.federation.delivery.max-attempts", defaultValue = "8")
	int maxDeliveryAttempts;

	@ConfigProperty(name = "gitshark.federation.dev-allow-insecure", defaultValue = "false")
	boolean devAllowInsecure;

	public boolean enabled()
	{
		return enabled;
	}

	/** True only when federation is enabled AND a usable base URL is configured. */
	public boolean operational()
	{
		return enabled && validatedBaseUrl().isPresent();
	}

	/**
	 * The validated public origin without a trailing slash, or empty if unusable. A usable base URL
	 * is absolute, http(s), has a host, and is not a loopback/localhost address.
	 */
	public Optional<String> validatedBaseUrl()
	{
		return baseUrl
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.filter(value -> isUsableOrigin(value, devAllowInsecure))
			.map(FederationConfig::stripTrailingSlash);
	}

	/** The base URL or a thrown error — use only on paths already guarded by {@link #operational()}. */
	public String baseUrl()
	{
		return validatedBaseUrl().orElseThrow(
			() -> new IllegalStateException("gitshark.federation.base-url is unset or not a usable public origin"));
	}

	public List<String> peers()
	{
		return peerAllowlist.orElse(List.of());
	}

	public int maxDeliveryAttempts()
	{
		return maxDeliveryAttempts;
	}

	/**
	 * Dev/local-only escape hatch: when true, the SSRF guard permits {@code http} and
	 * loopback/private targets (the peer allowlist is still enforced). Never enable in production —
	 * it exists so two instances can federate on one machine for testing.
	 */
	public boolean devAllowInsecure()
	{
		return devAllowInsecure;
	}

	/** Whether the given host is on the peer allowlist (case-insensitive). */
	public boolean peerAllowed(String host)
	{
		if (host == null)
		{
			return false;
		}
		String normalized = host.toLowerCase(Locale.ROOT);
		return peers().stream().map(p -> p.trim().toLowerCase(Locale.ROOT)).anyMatch(normalized::equals);
	}

	private static boolean isUsableOrigin(String value, boolean allowLoopback)
	{
		try
		{
			URI uri = new URI(value);
			if (!uri.isAbsolute() || uri.getHost() == null)
			{
				return false;
			}
			String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https"))
			{
				return false;
			}
			if (allowLoopback)
			{
				return true; // dev-only: permit localhost/loopback base URLs for local two-host trials
			}
			String host = uri.getHost().toLowerCase(Locale.ROOT);
			return !host.equals("localhost") && !host.startsWith("127.") && !host.equals("::1")
				&& !host.equals("[::1]");
		}
		catch (URISyntaxException e)
		{
			return false;
		}
	}

	private static String stripTrailingSlash(String value)
	{
		return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
	}

}
