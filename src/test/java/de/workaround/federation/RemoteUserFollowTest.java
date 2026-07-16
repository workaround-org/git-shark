package de.workaround.federation;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.workaround.model.DeliveryTask;
import de.workaround.model.RemoteActor;
import de.workaround.model.RemoteFollow;
import de.workaround.model.RemoteUserFollow;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Following a remote user fans out to a repository follow per public repository the remote
 * advertises; unfollowing the user undoes them all. The remote's repositories collection is stubbed
 * (no live HTTP), and each repository actor is seeded into the remote-actor cache.
 */
@QuarkusTest
class RemoteUserFollowTest
{
	@Inject
	RemoteFollowService service;

	@Inject
	RemoteFollow.Repo follows;

	@Inject
	RemoteUserFollow.Repo userFollows;

	@Inject
	DeliveryTask.Repo deliveries;

	@Inject
	User.Repo userRepo;

	/** Canned repositories collection per person actor id, consulted by the stubbed directory. */
	private final Map<String, List<String>> canned = new HashMap<>();

	@BeforeEach
	void installDirectoryStub()
	{
		canned.clear();
		QuarkusMock.installMockForType(new RemoteRepositoryDirectory()
		{
			@Override
			public List<String> repositoriesOf(String personActorId)
			{
				return canned.getOrDefault(personActorId, List.of());
			}
		}, RemoteRepositoryDirectory.class);
	}

	@Test
	void followUserFansOutToRepositoryFollows()
	{
		User user = persistUser("ruf-alice-" + unique());
		String person = "https://peer.test/ap/users/bob-" + unique();
		String repoOne = "https://peer.test/ap/repos/bob/one-" + unique();
		String repoTwo = "https://peer.test/ap/repos/bob/two-" + unique();
		seedRemoteActor(repoOne);
		seedRemoteActor(repoTwo);
		canned.put(person, List.of(repoOne, repoTwo));

		RemoteUserFollow uf = service.followUser(user, person);

		assertEquals(person, uf.remoteUserActorId);
		List<RemoteFollow> repos = service.repositoriesOfFollowedUser(user, person);
		assertEquals(2, repos.size());
		assertTrue(repos.stream().allMatch(f -> person.equals(f.viaUserActorId)));
		assertTrue(repos.stream().allMatch(f -> f.state == RemoteFollow.State.PENDING));
		assertTrue(deliveries.findDue(Instant.now()).stream()
			.anyMatch(t -> t.targetInbox.equals(repoOne + "/inbox") && t.payload.contains("\"Follow\"")));
		assertTrue(deliveries.findDue(Instant.now()).stream()
			.anyMatch(t -> t.targetInbox.equals(repoTwo + "/inbox") && t.payload.contains("\"Follow\"")));
	}

	@Test
	void followUserIsIdempotent()
	{
		User user = persistUser("ruf-ida-" + unique());
		String person = "https://peer.test/ap/users/bob-" + unique();
		String repoOne = "https://peer.test/ap/repos/bob/one-" + unique();
		seedRemoteActor(repoOne);
		canned.put(person, List.of(repoOne));

		RemoteUserFollow first = service.followUser(user, person);
		RemoteUserFollow second = service.followUser(user, person);

		assertEquals(first.id, second.id);
		assertEquals(1, service.listUsers(user).size());
		assertEquals(1, service.repositoriesOfFollowedUser(user, person).size());
	}

	@Test
	void unfollowUserUndoesEveryDerivedFollow()
	{
		User user = persistUser("ruf-frank-" + unique());
		String person = "https://peer.test/ap/users/bob-" + unique();
		String repoOne = "https://peer.test/ap/repos/bob/one-" + unique();
		String repoTwo = "https://peer.test/ap/repos/bob/two-" + unique();
		seedRemoteActor(repoOne);
		seedRemoteActor(repoTwo);
		canned.put(person, List.of(repoOne, repoTwo));
		RemoteUserFollow uf = service.followUser(user, person);

		service.unfollowUser(user, uf.id);

		assertTrue(service.listUsers(user).isEmpty());
		assertTrue(service.repositoriesOfFollowedUser(user, person).isEmpty());
		assertTrue(deliveries.findDue(Instant.now()).stream()
			.anyMatch(t -> t.targetInbox.equals(repoOne + "/inbox") && t.payload.contains("\"Undo\"")));
	}

	@Transactional
	void seedRemoteActor(String actorId)
	{
		RemoteActor actor = new RemoteActor();
		actor.actorId = actorId;
		actor.inbox = actorId + "/inbox";
		actor.publicKeyPem = "-----BEGIN PUBLIC KEY-----\nstub\n-----END PUBLIC KEY-----";
		actor.fetchedAt = Instant.now();
		actor.persist();
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
