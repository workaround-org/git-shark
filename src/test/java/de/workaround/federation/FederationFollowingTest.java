package de.workaround.federation;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.DeliveryTask;
import de.workaround.model.RemoteActor;
import de.workaround.model.Repository;
import de.workaround.model.RepositoryFollower;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Section 4: Follow records a follower and enqueues Accept; Undo removes the follower. */
@QuarkusTest
class FederationFollowingTest
{
	@Inject
	FollowHandler followHandler;

	@Inject
	UndoHandler undoHandler;

	@Inject
	GitRepositoryService service;

	@Inject
	ActorUris uris;

	@Inject
	RepositoryFollower.Repo followers;

	@Inject
	RemoteActor.Repo remoteActors;

	@Inject
	DeliveryTask.Repo deliveries;

	@Inject
	User.Repo userRepo;

	@Inject
	ObjectMapper mapper;

	@Test
	void followRecordsFollowerAndEnqueuesAccept()
	{
		User owner = persistUser("fed-fol-bob-" + unique());
		Repository repo = createRepo(owner, "lib", Repository.Visibility.PUBLIC);
		String follower = "https://peer.test/ap/users/frank-" + unique();
		String followerInbox = follower + "/inbox";
		seedRemoteActor(follower, followerInbox);

		followHandler.handle(follow(follower, uris.repository(repo)));

		assertTrue(hasFollower(repo, follower));
		assertTrue(deliveries.findDue(Instant.now()).stream()
			.anyMatch(t -> t.targetInbox.equals(followerInbox) && t.payload.contains("\"Accept\"")));
	}

	@Test
	void undoRemovesFollower()
	{
		User owner = persistUser("fed-fol-carol-" + unique());
		Repository repo = createRepo(owner, "lib", Repository.Visibility.PUBLIC);
		String follower = "https://peer.test/ap/users/gwen-" + unique();
		seedRemoteActor(follower, follower + "/inbox");

		String repoActor = uris.repository(repo);
		followHandler.handle(follow(follower, repoActor));
		assertTrue(hasFollower(repo, follower));

		undoHandler.handle(undoFollow(follower, repoActor));
		assertFalse(hasFollower(repo, follower));
	}

	@Test
	void followOfPrivateRepositoryIsIgnored()
	{
		User owner = persistUser("fed-fol-dave-" + unique());
		Repository repo = createRepo(owner, "secret", Repository.Visibility.PRIVATE);
		String follower = "https://peer.test/ap/users/mallory-" + unique();

		followHandler.handle(follow(follower, uris.repository(repo)));

		assertFalse(hasFollower(repo, follower));
	}

	private ObjectNode follow(String actor, String object)
	{
		ObjectNode node = mapper.createObjectNode();
		node.put("id", "https://peer.test/activities/" + UUID.randomUUID());
		node.put("type", "Follow");
		node.put("actor", actor);
		node.put("object", object);
		return node;
	}

	private ObjectNode undoFollow(String actor, String repoActor)
	{
		ObjectNode node = mapper.createObjectNode();
		node.put("id", "https://peer.test/activities/" + UUID.randomUUID());
		node.put("type", "Undo");
		node.put("actor", actor);
		ObjectNode follow = node.putObject("object");
		follow.put("type", "Follow");
		follow.put("actor", actor);
		follow.put("object", repoActor);
		return node;
	}

	@Transactional
	boolean hasFollower(Repository repo, String followerActorId)
	{
		Repository managed = service.find(repo.ownerHandle(), repo.name).orElseThrow();
		return followers.findByRepositoryAndFollowerActorId(managed, followerActorId).isPresent();
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
	Repository createRepo(User owner, String name, Repository.Visibility visibility)
	{
		return service.create(owner, name + "-" + unique(), visibility, null);
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
