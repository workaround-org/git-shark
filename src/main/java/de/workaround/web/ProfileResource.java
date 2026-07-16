package de.workaround.web;

import java.util.List;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The public profile page for a person at {@code /users/{username}}: their display name and a
 * visibility-filtered list of the repositories they own. Reached by clicking a person hit on the
 * search results page. Organisation profiles live under {@code /orgs/{name}}.
 */
@Path("/users")
@Produces(MediaType.TEXT_HTML)
public class ProfileResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance profile(User profile, List<Repository> repos, User user);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService repositories;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	User.Repo users;

	@GET
	@Path("{username}")
	public TemplateInstance page(@PathParam("username") String username)
	{
		User profile = users.findByUsername(username).orElseThrow(NotFoundException::new);
		User user = currentUser.get();
		List<Repository> visible = repositories.listOwnedBy(profile).stream()
			.filter(repo -> accessPolicy.canRead(user, repo))
			.toList();
		return Templates.profile(profile, visible, user);
	}
}
