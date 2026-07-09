package de.workaround.mirror;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;

import org.eclipse.jgit.transport.URIish;

import de.workaround.federation.RemoteUrlGuard;

/**
 * SSRF guard for mirror targets: the server opens outbound connections to these user-supplied
 * URLs. Only {@code https} and {@code ssh} (including scp-like {@code git@host:path}) are
 * accepted, never the instance's own host (loop protection), and never a host resolving to a
 * private/loopback address — unless the dev-only insecure flag is set, which permits {@code http}
 * and private targets for local testing. The own-host check is enforced in every mode.
 */
public final class MirrorUrlValidator
{
	private MirrorUrlValidator()
	{
	}

	/** Validates and returns the parsed URI, or throws {@link InvalidMirrorUrlException}. */
	public static URIish validate(String url, boolean allowInsecure, String ownHost)
	{
		if (url == null || url.isBlank())
		{
			throw new InvalidMirrorUrlException("Mirror URL must not be empty");
		}
		URIish uri;
		try
		{
			uri = new URIish(url.trim());
		}
		catch (java.net.URISyntaxException e)
		{
			throw new InvalidMirrorUrlException("Malformed mirror URL: " + url);
		}
		String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase(Locale.ROOT);
		boolean ssh = "ssh".equals(scheme) || (scheme == null && uri.isRemote());
		boolean https = "https".equals(scheme) || (allowInsecure && "http".equals(scheme));
		if (!ssh && !https)
		{
			throw new InvalidMirrorUrlException("Only https:// and ssh:// (or git@host:path) mirror URLs are allowed");
		}
		String host = uri.getHost();
		if (host == null || host.isEmpty())
		{
			throw new InvalidMirrorUrlException("Mirror URL has no host: " + url);
		}
		if (ownHost != null && host.equalsIgnoreCase(ownHost))
		{
			throw new InvalidMirrorUrlException("Mirroring to this instance itself is not allowed");
		}
		if (!allowInsecure)
		{
			for (InetAddress address : resolve(host))
			{
				if (RemoteUrlGuard.isNonPublic(address))
				{
					throw new InvalidMirrorUrlException("Mirror host resolves to a non-public address: " + host);
				}
			}
		}
		return uri;
	}

	private static InetAddress[] resolve(String host)
	{
		try
		{
			return InetAddress.getAllByName(host);
		}
		catch (UnknownHostException e)
		{
			throw new InvalidMirrorUrlException("Mirror host does not resolve: " + host);
		}
	}

}
