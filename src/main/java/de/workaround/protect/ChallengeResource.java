package de.workaround.protect;

import java.net.URI;

import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * The bot check a rate-limited visitor lands on. Renders the configured widget (Turnstile or
 * hCaptcha); on a token the provider confirms, mints a {@link HumanPass} cookie and returns the
 * visitor to the page they wanted.
 *
 * <p>The page exists only while a captcha is fully configured — otherwise there is nothing to solve
 * and the route answers 404 instead of showing an empty form.
 */
@Path("/challenge")
public class ChallengeResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance challenge(String siteKey, String scriptUrl, String widgetClass,
			String responseField, String redirect, String error);
	}

	@Inject
	ProtectionConfig config;

	@Inject
	CaptchaVerifier verifier;

	@Inject
	HumanPass humanPass;

	@Inject
	ClientAddress clientAddress;

	@GET
	@Produces(MediaType.TEXT_HTML)
	public Response page(@QueryParam("redirect") String redirect)
	{
		requireCaptcha();
		return uncached(Response.ok(render(safeRedirect(redirect), null)));
	}

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	@Produces(MediaType.TEXT_HTML)
	public Response solve(MultivaluedMap<String, String> form, @Context UriInfo uriInfo)
	{
		requireCaptcha();
		String redirect = safeRedirect(form.getFirst("redirect"));
		String token = form.getFirst(config.provider().responseField());
		if (!verifier.verify(token, clientAddress.ip()))
		{
			return uncached(Response.status(Response.Status.FORBIDDEN)
				.entity(render(redirect, "That check did not go through. Please try again.")));
		}
		NewCookie pass = new NewCookie.Builder(HumanPass.COOKIE_NAME)
			.value(humanPass.issue())
			.path("/")
			.maxAge(humanPass.cookieMaxAge())
			.httpOnly(true)
			.sameSite(NewCookie.SameSite.LAX)
			.secure("https".equalsIgnoreCase(uriInfo.getRequestUri().getScheme()))
			.build();
		return uncached(Response.seeOther(URI.create(redirect)).cookie(pass));
	}

	/**
	 * The check is per-visitor and short-lived — the widget's own token certainly is — so no proxy or
	 * browser may keep any of it. Without this the page carries no cache directive at all and is
	 * heuristically cacheable.
	 */
	private static Response uncached(Response.ResponseBuilder builder)
	{
		return builder.header(HttpHeaders.CACHE_CONTROL, ExpensiveRequestFilter.NO_STORE).build();
	}

	private TemplateInstance render(String redirect, String error)
	{
		CaptchaProvider provider = config.provider();
		return Templates.challenge(config.siteKey().orElse(""), provider.scriptUrl(), provider.widgetClass(),
			provider.responseField(), redirect, error);
	}

	private void requireCaptcha()
	{
		if (!config.captchaConfigured())
		{
			throw new NotFoundException();
		}
	}

	/**
	 * Only server-relative single-slash paths are accepted, so a crafted {@code ?redirect=} cannot
	 * turn the challenge into an open redirect towards another host.
	 */
	private static String safeRedirect(String redirect)
	{
		if (redirect == null || !redirect.startsWith("/") || redirect.startsWith("//"))
		{
			return "/";
		}
		return redirect;
	}
}
