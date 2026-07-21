package de.workaround.api;

import de.workaround.search.SearchResults;
import de.workaround.search.SearchService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * JSON search over repositories and persons at {@code /api/v1/search?q=<term>}. Open to anonymous
 * callers; repository hits follow the same visibility rule as the rest of the API (public repos plus
 * the caller's own private ones). A blank query returns empty arrays rather than an error.
 */
@Path("/api/v1/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchApiResource
{
	@Inject
	SearchService search;

	@Inject
	ApiPrincipal principal;

	@GET
	public ApiModels.SearchView search(@QueryParam("q") String q)
	{
		SearchResults results = search.search(principal.orNull(), q);
		return new ApiModels.SearchView(
			results.repositories().stream().map(ApiModels.RepositoryView::shallow).toList(),
			results.persons().stream().map(ApiModels.PersonView::of).toList());
	}
}
