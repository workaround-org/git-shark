package de.workaround.http;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * HTTP Basic authentication for Git smart HTTP. The Basic password is a personal access
 * token; the username part is informational only (like GitHub PATs). Requests without
 * credentials continue anonymously — the GitServlet decides whether anonymous is enough.
 * Invalid credentials are rejected immediately. All 401 responses carry a Basic challenge
 * so Git clients prompt for credentials and retry.
 */
@WebFilter(urlPatterns = "/git/*")
public class GitBasicAuthFilter extends HttpFilter
{
	private static final String CHALLENGE = "Basic realm=\"git-shark\"";

	@Inject
	AccessTokenService tokenService;

	@Override
	protected void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
		throws IOException, ServletException
	{
		String header = request.getHeader("Authorization");
		if (header != null && header.regionMatches(true, 0, "Basic ", 0, 6))
		{
			Optional<User> user = decodePassword(header).flatMap(tokenService::authenticate);
			if (user.isEmpty())
			{
				response.setHeader("WWW-Authenticate", CHALLENGE);
				response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
				return;
			}
			request.setAttribute(GitHttpServlet.ATTR_USER, user.get());
		}
		chain.doFilter(request, new ChallengeOn401(response));
	}

	private static Optional<String> decodePassword(String header)
	{
		try
		{
			String decoded = new String(Base64.getDecoder().decode(header.substring(6).trim()),
				StandardCharsets.UTF_8);
			int colon = decoded.indexOf(':');
			return colon >= 0 ? Optional.of(decoded.substring(colon + 1)) : Optional.empty();
		}
		catch (IllegalArgumentException e)
		{
			return Optional.empty();
		}
	}

	/**
	 * JGit's GitServlet sends bare 401s; git clients only retry with credentials when a
	 * WWW-Authenticate challenge is present, so add it before the response is committed.
	 */
	private static final class ChallengeOn401 extends HttpServletResponseWrapper
	{
		ChallengeOn401(HttpServletResponse response)
		{
			super(response);
		}

		@Override
		public void sendError(int sc) throws IOException
		{
			challenge(sc);
			super.sendError(sc);
		}

		@Override
		public void sendError(int sc, String msg) throws IOException
		{
			challenge(sc);
			super.sendError(sc, msg);
		}

		@Override
		public void setStatus(int sc)
		{
			challenge(sc);
			super.setStatus(sc);
		}

		private void challenge(int sc)
		{
			if (sc == HttpServletResponse.SC_UNAUTHORIZED)
			{
				setHeader("WWW-Authenticate", CHALLENGE);
			}
		}
	}

}
