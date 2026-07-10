package de.workaround.web;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

/**
 * Exposes the current request's location to templates. The header's Log in link points at
 * {@code /login?redirect=<current path + query>} so the user returns to the page they were on
 * after the OIDC code flow instead of being dumped on the dashboard.
 */
@Named("currentRequest")
@RequestScoped
public class CurrentRequest
{
	@Inject
	RoutingContext routingContext;

	public String loginUrl()
	{
		// uri() is the server-relative path including the query string
		return "/login?redirect=" + URLEncoder.encode(routingContext.request().uri(), StandardCharsets.UTF_8);
	}

}
