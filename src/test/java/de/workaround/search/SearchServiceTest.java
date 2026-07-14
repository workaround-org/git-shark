package de.workaround.search;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SearchServiceTest
{
	@Inject
	SearchService search;

	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo userRepo;

	@Test
	void findsPublicRepoAndPersonButHidesPrivateFromStrangers()
	{
		String tok = "srch" + shortId();
		User owner = persistUser("owner-" + tok, null);
		repositories.create(owner, "repo-" + tok, Repository.Visibility.PUBLIC, "the " + tok + " project");
		repositories.create(owner, "priv-" + tok, Repository.Visibility.PRIVATE, null);

		// anonymous: public repo + person (username carries the token), private repo excluded
		SearchResults anon = search.search(null, tok);
		assertTrue(repoNames(anon).contains("repo-" + tok), "public repo should match");
		assertFalse(repoNames(anon).contains("priv-" + tok), "private repo must be hidden from anonymous");
		assertTrue(usernames(anon).contains("owner-" + tok), "person should match by username");

		// owner sees their own private repo too
		SearchResults asOwner = search.search(owner, tok);
		assertTrue(repoNames(asOwner).contains("priv-" + tok), "owner should see their private repo");
	}

	@Test
	void matchesPersonByDisplayNameCaseInsensitively()
	{
		String tok = "disp" + shortId();
		persistUser("plainhandle-" + shortId(), "Zed " + tok + " Person");

		SearchResults results = search.search(null, tok.toUpperCase());
		assertTrue(results.persons().stream().anyMatch(u -> u.displayName != null && u.displayName.contains(tok)),
			"person should match by display name, case-insensitively");
	}

	@Test
	void blankQueryReturnsEmpty()
	{
		assertTrue(search.search(null, "   ").repositories().isEmpty());
		assertTrue(search.search(null, "   ").persons().isEmpty());
		assertTrue(search.search(null, null).persons().isEmpty());
	}

	private static List<String> repoNames(SearchResults r)
	{
		return r.repositories().stream().map(repo -> repo.name).toList();
	}

	private static List<String> usernames(SearchResults r)
	{
		return r.persons().stream().map(u -> u.username).toList();
	}

	private static String shortId()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

	@Transactional
	User persistUser(String name, String displayName)
	{
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.displayName = displayName;
		user.persist();
		return user;
	}
}
