package de.workaround.search;

import java.util.List;

import de.workaround.model.Repository;
import de.workaround.model.User;

/**
 * The hits of a single search, split by kind. Repositories are already filtered to what the
 * searching user may read; persons are onboarded users only. Both lists are empty for a blank query.
 */
public record SearchResults(List<Repository> repositories, List<User> persons)
{
	public static SearchResults empty()
	{
		return new SearchResults(List.of(), List.of());
	}
}
