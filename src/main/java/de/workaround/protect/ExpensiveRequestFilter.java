package de.workaround.protect;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Meters the expensive rendered pages (see {@link ExpensivePaths}) per caller: logged-in users
 * against their own budget, anonymous visitors against a smaller one keyed by client IP — a crawler
 * without an account is exactly what this defends against.
 *
 * <p>Over budget, the response depends on configuration: with a captcha configured the visitor is
 * sent to {@code /challenge} and can prove they are human (which mints a pass cookie that lifts the
 * budget); without one the request is refused with {@code 429} and a {@code Retry-After}. Only GETs
 * are metered — form POSTs already require a session, and a challenge in the middle of one would
 * lose the submitted body.
 */
@Provider
public class ExpensiveRequestFilter implements ContainerRequestFilter
{
	@Inject
	ProtectionConfig config;

	@Inject
	RateLimiter limiter;

	@Inject
	HumanPass humanPass;

	@Inject
	ClientAddress clientAddress;

	@Inject
	SecurityIdentity identity;

	@Override
	public void filter(ContainerRequestContext context)
	{
		if (!config.enabled() || !"GET".equals(context.getMethod()))
		{
			return;
		}
		if (!ExpensivePaths.isExpensive(context.getUriInfo().getPath()))
		{
			return;
		}
		if (carriesValidPass(context))
		{
			return;
		}
		boolean loggedIn = !identity.isAnonymous();
		String key = loggedIn
			? "user:" + identity.getPrincipal().getName()
			: "ip:" + clientAddress.ip();
		int limit = loggedIn ? config.userLimit() : config.anonymousLimit();
		if (limiter.tryAcquire(key, limit, config.window()))
		{
			return;
		}
		context.abortWith(config.captchaConfigured() ? challenge(context) : refusal());
	}

	private boolean carriesValidPass(ContainerRequestContext context)
	{
		Cookie cookie = context.getCookies().get(HumanPass.COOKIE_NAME);
		return cookie != null && humanPass.valid(cookie.getValue());
	}

	private Response challenge(ContainerRequestContext context)
	{
		String target = context.getUriInfo().getRequestUri().getPath();
		String query = context.getUriInfo().getRequestUri().getRawQuery();
		if (query != null && !query.isEmpty())
		{
			target = target + "?" + query;
		}
		String location = "/challenge?redirect=" + URLEncoder.encode(target, StandardCharsets.UTF_8);
		return Response.seeOther(URI.create(location)).build();
	}

	private Response refusal()
	{
		return Response.status(Response.Status.TOO_MANY_REQUESTS)
			.header("Retry-After", Math.max(1, config.window().toSeconds()))
			.type(MediaType.TEXT_PLAIN)
			.entity("Too many expensive requests. Please slow down and try again shortly.\n")
			.build();
	}
}
