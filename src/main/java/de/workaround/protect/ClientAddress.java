package de.workaround.protect;

import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;

/**
 * The caller's IP address, used as the rate-limit key for anonymous visitors. Read from the Vert.x
 * remote address, which already reflects {@code X-Forwarded-For} because
 * {@code quarkus.http.proxy.allow-x-forwarded} is on — behind the ingress the peer address would
 * otherwise be the proxy and every visitor would share one budget.
 */
@RequestScoped
public class ClientAddress
{
	@Inject
	RoutingContext routingContext;

	public String ip()
	{
		SocketAddress remote = routingContext.request().remoteAddress();
		if (remote == null)
		{
			return "unknown";
		}
		String host = remote.hostAddress() != null ? remote.hostAddress() : remote.host();
		return host == null || host.isBlank() ? "unknown" : host;
	}
}
