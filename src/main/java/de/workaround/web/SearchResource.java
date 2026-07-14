package de.workaround.web;

import java.util.List;

import de.workaround.account.CurrentUser;
import de.workaround.model.Repository;
import de.workaround.model.User;
import de.workaround.search.SearchResults;
import de.workaround.search.SearchService;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * The server-rendered search page at {@code /search?q=<term>}. Groups repository and person hits;
 * repository visibility follows the same rule as everywhere else. A blank query renders the empty
 * results page rather than an error.
 */
@Path("/search")
@Produces(MediaType.TEXT_HTML)
public class SearchResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance results(String query, List<Repository> repositories, List<User> persons,
			User user);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	SearchService search;

	@GET
	public TemplateInstance search(@QueryParam("q") String q)
	{
		User user = currentUser.get();
		SearchResults results = search.search(user, q);
		return Templates.results(q == null ? "" : q, results.repositories(), results.persons(), user);
	}
}
