package de.workaround.search;

import java.util.List;
import java.util.Locale;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Simple case-insensitive substring search across repositories and persons, shared by the REST API
 * and the server-rendered UI so both return the same hits. Repository visibility is enforced by
 * reusing {@link GitRepositoryService#listVisibleTo(User)} before filtering, so a search can never
 * surface a private repository the searcher cannot already read. No ranking or full-text in v1.
 */
@ApplicationScoped
public class SearchService
{
	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo users;

	/**
	 * @param actor the searching user, or {@code null} for an anonymous request (public repos only)
	 * @param query the raw search term; a null or blank query yields {@link SearchResults#empty()}
	 */
	public SearchResults search(User actor, String query)
	{
		if (query == null || query.isBlank())
		{
			return SearchResults.empty();
		}
		String needle = query.trim().toLowerCase(Locale.ROOT);
		List<Repository> repos = repositories.listVisibleTo(actor).stream()
			.filter(repo -> matches(repo, needle))
			.toList();
		List<User> persons = users.search("%" + needle + "%");
		return new SearchResults(repos, persons);
	}

	private static boolean matches(Repository repo, String needle)
	{
		return contains(repo.ownerHandle(), needle) || contains(repo.name, needle)
			|| contains(repo.description, needle);
	}

	private static boolean contains(String field, String needle)
	{
		return field != null && field.toLowerCase(Locale.ROOT).contains(needle);
	}
}
