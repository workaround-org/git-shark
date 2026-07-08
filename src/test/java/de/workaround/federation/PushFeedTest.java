package de.workaround.federation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.model.ReceivedPush;
import de.workaround.model.RemoteActor;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Follower-side feed of received {@code Push} activities: pushes from followed repositories are
 * stored and listed for the following user; pushes from unfollowed actors are ignored.
 */
@QuarkusTest
class PushFeedTest
{
	@Inject
	RemoteFollowService service;

	@Inject
	ActivityDispatcher dispatcher;

	@Inject
	ReceivedPush.Repo pushes;

	@Inject
	User.Repo userRepo;

	@Inject
	ObjectMapper mapper;

	@Test
	void pushFromFollowedRepoIsStoredAndListed()
	{
		User user = persistUser("pf-alice-" + unique());
		String remote = "https://peer.test/ap/repos/bob/lib-" + unique();
		seedRemoteActor(remote, remote + "/inbox");
		service.follow(user, remote);

		dispatcher.dispatch(push(remote, "Pushed 2 commit(s) to refs/heads/main"));

		List<ReceivedPush> feed = service.recentPushes(user);
		assertEquals(1, feed.size());
		assertEquals(remote, feed.get(0).remoteActorId);
		assertEquals("Pushed 2 commit(s) to refs/heads/main", feed.get(0).summary);
		assertEquals("refs/heads/main", feed.get(0).target);
	}

	@Test
	void pushFromUnfollowedActorIsIgnored()
	{
		User user = persistUser("pf-bob-" + unique());
		String stranger = "https://peer.test/ap/repos/mallory/junk-" + unique();

		dispatcher.dispatch(push(stranger, "Pushed 1 commit(s) to refs/heads/main"));

		assertTrue(service.recentPushes(user).isEmpty());
		assertTrue(findByActor(stranger).isEmpty());
	}

	@Test
	void duplicatePushActivityIsStoredOnce()
	{
		User user = persistUser("pf-carol-" + unique());
		String remote = "https://peer.test/ap/repos/bob/lib-" + unique();
		seedRemoteActor(remote, remote + "/inbox");
		service.follow(user, remote);

		ObjectNode push = push(remote, "Pushed 1 commit(s) to refs/heads/main");
		dispatcher.dispatch(push);
		dispatcher.dispatch(push);

		assertEquals(1, service.recentPushes(user).size());
	}

	@Test
	void feedOnlyShowsPushesOfOwnFollows()
	{
		User follower = persistUser("pf-dan-" + unique());
		User other = persistUser("pf-erin-" + unique());
		String remote = "https://peer.test/ap/repos/bob/lib-" + unique();
		seedRemoteActor(remote, remote + "/inbox");
		service.follow(follower, remote);

		dispatcher.dispatch(push(remote, "Pushed 1 commit(s) to refs/heads/main"));

		assertEquals(1, service.recentPushes(follower).size());
		assertTrue(service.recentPushes(other).isEmpty());
	}

	private ObjectNode push(String actor, String summary)
	{
		ObjectNode node = mapper.createObjectNode();
		node.put("id", actor + "/activities/" + UUID.randomUUID());
		node.put("type", "Push");
		node.put("actor", actor);
		node.put("context", actor);
		node.put("target", "refs/heads/main");
		node.put("summary", summary);
		return node;
	}

	@Transactional
	List<ReceivedPush> findByActor(String actorId)
	{
		return pushes.findByRemoteActorIds(List.of(actorId));
	}

	@Transactional
	void seedRemoteActor(String actorId, String inbox)
	{
		RemoteActor actor = new RemoteActor();
		actor.actorId = actorId;
		actor.inbox = inbox;
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
