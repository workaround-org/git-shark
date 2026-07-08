package de.workaround.federation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.model.DeliveryTask;
import de.workaround.model.RemoteActor;
import de.workaround.model.RemoteFollow;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Outbound following of a remote repository: a signed {@code Follow} is enqueued and recorded as
 * PENDING, an inbound {@code Accept} confirms it, and unfollowing enqueues an {@code Undo}.
 */
@QuarkusTest
class RemoteFollowServiceTest
{
	@Inject
	RemoteFollowService service;

	@Inject
	ActivityDispatcher dispatcher;

	@Inject
	ActorUris uris;

	@Inject
	RemoteFollow.Repo follows;

	@Inject
	DeliveryTask.Repo deliveries;

	@Inject
	User.Repo userRepo;

	@Inject
	ObjectMapper mapper;

	@Test
	void followByActorUrlRecordsPendingAndEnqueuesSignedFollow()
	{
		User user = persistUser("rf-alice-" + unique());
		String remote = "https://peer.test/ap/repos/bob/lib-" + unique();
		seedRemoteActor(remote, remote + "/inbox");

		RemoteFollow follow = service.follow(user, remote);

		assertEquals(RemoteFollow.State.PENDING, follow.state);
		assertEquals(remote, follow.remoteActorId);
		String personKeyId = uris.keyId(uris.person(user));
		assertTrue(deliveries.findDue(Instant.now()).stream()
			.anyMatch(t -> t.targetInbox.equals(remote + "/inbox")
				&& t.signerKeyId.equals(personKeyId)
				&& t.payload.contains("\"Follow\"")
				&& t.payload.contains(follow.followActivityId)));
	}

	@Test
	void followTwiceIsIdempotent()
	{
		User user = persistUser("rf-ida-" + unique());
		String remote = "https://peer.test/ap/repos/bob/lib-" + unique();
		seedRemoteActor(remote, remote + "/inbox");

		RemoteFollow first = service.follow(user, remote);
		RemoteFollow second = service.follow(user, remote);

		assertEquals(first.id, second.id);
	}

	@Test
	void followOfUnresolvableActorFails()
	{
		User user = persistUser("rf-eve-" + unique());

		assertThrows(RemoteFollowService.RemoteFollowException.class,
			() -> service.follow(user, "https://not-allowlisted.test/ap/repos/x/y"));
	}

	@Test
	void inboundAcceptMarksFollowAccepted()
	{
		User user = persistUser("rf-carol-" + unique());
		String remote = "https://peer.test/ap/repos/bob/lib-" + unique();
		seedRemoteActor(remote, remote + "/inbox");
		RemoteFollow follow = service.follow(user, remote);

		dispatcher.dispatch(accept(remote, follow.followActivityId));

		assertEquals(RemoteFollow.State.ACCEPTED, stateOf(follow.id));
	}

	@Test
	void acceptFromWrongActorIsIgnored()
	{
		User user = persistUser("rf-dan-" + unique());
		String remote = "https://peer.test/ap/repos/bob/lib-" + unique();
		seedRemoteActor(remote, remote + "/inbox");
		RemoteFollow follow = service.follow(user, remote);

		dispatcher.dispatch(accept("https://peer.test/ap/repos/mallory/other", follow.followActivityId));

		assertEquals(RemoteFollow.State.PENDING, stateOf(follow.id));
	}

	@Test
	void unfollowEnqueuesUndoAndRemovesFollow()
	{
		User user = persistUser("rf-frank-" + unique());
		String remote = "https://peer.test/ap/repos/bob/lib-" + unique();
		seedRemoteActor(remote, remote + "/inbox");
		RemoteFollow follow = service.follow(user, remote);

		service.unfollow(user, follow.id);

		assertTrue(list(user).isEmpty());
		assertTrue(deliveries.findDue(Instant.now()).stream()
			.anyMatch(t -> t.targetInbox.equals(remote + "/inbox")
				&& t.payload.contains("\"Undo\"")
				&& t.payload.contains(follow.followActivityId)));
	}

	private ObjectNode accept(String actor, String followActivityId)
	{
		ObjectNode node = mapper.createObjectNode();
		node.put("id", "https://peer.test/activities/" + UUID.randomUUID());
		node.put("type", "Accept");
		node.put("actor", actor);
		ObjectNode follow = node.putObject("object");
		follow.put("id", followActivityId);
		follow.put("type", "Follow");
		return node;
	}

	@Transactional
	RemoteFollow.State stateOf(UUID followId)
	{
		return follows.findById(followId).state;
	}

	@Transactional
	List<RemoteFollow> list(User user)
	{
		return follows.findByUser(userRepo.findById(user.id));
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
