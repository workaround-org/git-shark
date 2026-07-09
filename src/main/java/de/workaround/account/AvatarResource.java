package de.workaround.account;

import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;

/**
 * Serves user profile pictures. Public (unlike upload, which lives under /settings) so avatars can
 * embed on any page, including public repositories. Returns 404 when the user has no avatar.
 */
@Path("/users")
public class AvatarResource
{
	@Inject
	User.Repo users;

	@Inject
	AvatarService avatars;

	@GET
	@Path("/{username}/avatar")
	public Response avatar(@PathParam("username") String username)
	{
		User user = users.findByUsername(username)
			.filter(User::hasAvatar)
			.orElseThrow(NotFoundException::new);
		byte[] bytes = avatars.read(user).orElseThrow(NotFoundException::new);
		// Immutable is safe: every rendered avatar URL carries ?v=<avatarUpdatedAt>, so a new
		// upload changes the URL and the old cached response is never served for it.
		return Response.ok(bytes).type(user.avatarContentType)
			.header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
			.build();
	}

}
