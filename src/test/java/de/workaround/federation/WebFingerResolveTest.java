package de.workaround.federation;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-side WebFinger resolution against this instance's own endpoint: a repository handle
 * ({@code owner/name@host}) resolves to the repository's actor id, including a non-default port in
 * the host (the dev two-host trial setup).
 */
@QuarkusTest
@TestProfile(WebFingerResolveTest.SelfResolveProfile.class)
class WebFingerResolveTest
{
	public static class SelfResolveProfile implements QuarkusTestProfile
	{
		@Override
		public Map<String, String> getConfigOverrides()
		{
			return Map.of(
				"quarkus.http.test-port", "8082",
				"gitshark.federation.enabled", "true",
				"gitshark.federation.base-url", "http://localhost:8082",
				"gitshark.federation.dev-allow-insecure", "true",
				"gitshark.federation.peer-allowlist", "localhost,127.0.0.1");
		}
	}

	@Inject
	ActivityPubClient client;

	@Inject
	GitRepositoryService service;

	@Inject
	ActorUris uris;

	@Inject
	User.Repo userRepo;

	@Test
	void resolvesRepositoryHandleToActorId()
	{
		User owner = persistUser("wf-bob-" + unique());
		Repository repo = createRepo(owner, "lib-" + unique());

		Optional<String> actorId =
			client.resolveWebFinger(owner.username + "/" + repo.name, "localhost:8082");

		assertTrue(actorId.isPresent(), "handle must resolve via WebFinger");
		assertEquals(uris.repository(repo), actorId.get());
	}

	@Test
	void unknownHandleResolvesEmpty()
	{
		assertTrue(client.resolveWebFinger("nobody/nothing-" + unique(), "localhost:8082").isEmpty());
	}

	@Transactional
	Repository createRepo(User owner, String name)
	{
		return service.create(owner, name, Repository.Visibility.PUBLIC, null);
	}

	@Transactional
	User persistUser(String name)
	{
		return userRepo.findByOidcSubOptional(name).orElseGet(() -> {
			User user = new User();
			user.oidcSub = name;
			user.username = name;
			user.persist();
			return user;
		});
	}

	private static String unique()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
