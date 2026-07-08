package de.workaround.api;

import java.io.IOException;

import de.workaround.http.AccessTokenService;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.ext.Provider;

/**
 * Authenticates {@code /api/**} requests from an {@code Authorization: Bearer <token>} header, where the
 * token is a personal access token (the same secret used for Git-over-HTTP). A valid token populates
 * {@link ApiPrincipal}; a present-but-invalid token is rejected with 401. Requests without a token continue
 * anonymously — resources decide whether anonymous access is enough (public reads) or not (mutations).
 */
@Provider
public class ApiTokenAuthFilter implements ContainerRequestFilter
{
	private static final String BEARER = "Bearer ";

	@Inject
	AccessTokenService tokens;

	@Inject
	ApiPrincipal principal;

	@Override
	public void filter(ContainerRequestContext requestContext) throws IOException
	{
		String path = requestContext.getUriInfo().getPath();
		// getPath() is relative to the app root and carries no leading slash; be lenient either way.
		if (!path.startsWith("api/") && !path.startsWith("/api/"))
		{
			return;
		}
		String header = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
		if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length()))
		{
			return;
		}
		String token = header.substring(BEARER.length()).trim();
		tokens.authenticate(token)
			.ifPresentOrElse(principal::set, () -> {
				throw new NotAuthorizedException("Bearer realm=\"git-shark-api\"");
			});
	}
}
