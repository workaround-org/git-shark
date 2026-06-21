package de.workaround.model;

import java.time.Instant;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Confirms the federation entities persist and that Hibernate validates them against the
 * V2__federation.sql migration (schema-management strategy = validate).
 */
@QuarkusTest
class FederationEntityPersistenceTest
{
	@Inject
	FederationKey.Repo keys;

	@Inject
	RemoteActor.Repo remoteActors;

	@Inject
	RepositoryFollower.Repo followers;

	@Inject
	InboxActivity.Repo inbox;

	@Inject
	DeliveryTask.Repo deliveries;

	@Inject
	User.Repo users;

	@Test
	@TestTransaction
	void persistsFederationKey()
	{
		String ref = java.util.UUID.randomUUID().toString();
		FederationKey key = new FederationKey();
		key.actorType = FederationKey.ActorType.REPOSITORY;
		key.actorRef = ref;
		key.publicPem = "-----BEGIN PUBLIC KEY-----\npub\n-----END PUBLIC KEY-----";
		key.privatePem = "-----BEGIN PRIVATE KEY-----\npriv\n-----END PRIVATE KEY-----";
		key.persist();

		assertNotNull(key.id);
		FederationKey found = keys.findByActorTypeAndActorRef(FederationKey.ActorType.REPOSITORY, ref).orElseThrow();
		assertTrue(found.publicPem.contains("PUBLIC KEY"));
	}

	@Test
	@TestTransaction
	void persistsRemoteActor()
	{
		RemoteActor actor = new RemoteActor();
		actor.actorId = "https://peer.test/ap/repos/bob/project";
		actor.inbox = "https://peer.test/ap/repos/bob/project/inbox";
		actor.publicKeyPem = "-----BEGIN PUBLIC KEY-----\nremote\n-----END PUBLIC KEY-----";
		actor.persist();

		assertNotNull(actor.id);
		RemoteActor found = remoteActors.findByActorId("https://peer.test/ap/repos/bob/project").orElseThrow();
		assertEquals(actor.inbox, found.inbox);
	}

	@Test
	@TestTransaction
	void persistsRepositoryFollower()
	{
		User owner = new User();
		owner.oidcSub = "oidc-sub-fed-eve";
		owner.username = "eve";
		owner.persist();

		Repository repo = new Repository();
		repo.name = "lib";
		repo.owner = owner;
		repo.visibility = Repository.Visibility.PUBLIC;
		repo.persist();

		RepositoryFollower follower = new RepositoryFollower();
		follower.repository = repo;
		follower.followerActorId = "https://peer.test/ap/users/frank";
		follower.persist();

		assertNotNull(follower.id);
		assertTrue(followers.findByRepositoryAndFollowerActorId(repo, "https://peer.test/ap/users/frank").isPresent());
		assertEquals(1, followers.findByRepository(repo).size());
	}

	@Test
	@TestTransaction
	void persistsInboxActivityForDedup()
	{
		InboxActivity activity = new InboxActivity();
		activity.activityId = "https://peer.test/activities/123";
		activity.persist();

		assertNotNull(activity.id);
		assertTrue(inbox.findByActivityId("https://peer.test/activities/123").isPresent());
		assertFalse(inbox.findByActivityId("https://peer.test/activities/unknown").isPresent());
	}

	@Test
	@TestTransaction
	void persistsAndQueriesDueDeliveryTask()
	{
		DeliveryTask task = new DeliveryTask();
		task.targetInbox = "https://peer.test/ap/users/frank/inbox";
		task.actorKeyType = FederationKey.ActorType.REPOSITORY;
		task.actorKeyRef = "some-repo-id";
		task.signerKeyId = "https://shark.test/ap/repos/x/y#main-key";
		task.payload = "{\"type\":\"Push\"}";
		task.nextAttemptAt = Instant.now().minusSeconds(5);
		task.persist();

		assertNotNull(task.id);
		assertEquals(DeliveryTask.State.PENDING, task.state);
		assertFalse(deliveries.findDue(Instant.now()).isEmpty());
		assertTrue(deliveries.findDue(Instant.now().minusSeconds(3600)).isEmpty());
	}

}
