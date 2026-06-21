package de.workaround.federation;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * SSRF protection for outbound federation fetches/deliveries: only HTTPS, only hosts on the peer
 * allowlist, and never an address that resolves to a private, loopback, or link-local IP.
 */
@ApplicationScoped
public class RemoteUrlGuard
{
	@Inject
	FederationConfig config;

	/** Thrown when a URL is not safe to contact. */
	public static class UnsafeUrlException extends RuntimeException
	{
		public UnsafeUrlException(String message)
		{
			super(message);
		}
	}

	/** Validates the URL and returns it parsed, or throws {@link UnsafeUrlException}. */
	public URI requireSafe(String url)
	{
		URI uri;
		try
		{
			uri = URI.create(url);
		}
		catch (IllegalArgumentException e)
		{
			throw new UnsafeUrlException("Malformed URL: " + url);
		}
		boolean insecure = config.devAllowInsecure();
		String scheme = uri.getScheme();
		boolean schemeOk = scheme != null
			&& (scheme.equalsIgnoreCase("https") || (insecure && scheme.equalsIgnoreCase("http")));
		if (!schemeOk)
		{
			throw new UnsafeUrlException("Only https is allowed: " + url);
		}
		String host = uri.getHost();
		if (host == null)
		{
			throw new UnsafeUrlException("Missing host: " + url);
		}
		// The peer allowlist is always enforced, even in dev-insecure mode.
		if (!config.peerAllowed(host))
		{
			throw new UnsafeUrlException("Host not on peer allowlist: " + host);
		}
		if (!insecure)
		{
			for (InetAddress address : resolve(host))
			{
				if (isNonPublic(address))
				{
					throw new UnsafeUrlException("Host resolves to a non-public address: " + host);
				}
			}
		}
		return uri;
	}

	/** True for loopback, any-local, link-local, site-local (private), or multicast addresses. */
	public static boolean isNonPublic(InetAddress address)
	{
		return address.isLoopbackAddress() || address.isAnyLocalAddress()
			|| address.isLinkLocalAddress() || address.isSiteLocalAddress()
			|| address.isMulticastAddress();
	}

	private static InetAddress[] resolve(String host)
	{
		try
		{
			return InetAddress.getAllByName(host);
		}
		catch (UnknownHostException e)
		{
			throw new UnsafeUrlException("Host does not resolve: " + host);
		}
	}

}
