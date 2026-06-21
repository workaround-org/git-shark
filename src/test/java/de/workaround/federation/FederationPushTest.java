package de.workaround.federation;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.http.AccessTokenService;
import de.workaround.http.GitSmartHttpTest;
import de.workaround.model.DeliveryTask;
import de.workaround.model.FederationKey;
import de.workaround.model.OutboxActivity;
import de.workaround.model.RemoteActor;
import de.workaround.model.Repository;
import de.workaround.model.RepositoryFollower;
import de.workaround.model.User;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 5: a real authenticated HTTP push to a public federated repository emits a ForgeFed
 * {@code Push} into the outbox and enqueues delivery to followers, and never fails the push.
 */
@QuarkusTest
class FederationPushTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	AccessTokenService tokenService;

	@Inject
	OutboxActivity.Repo outbox;

	@Inject
	DeliveryTask.Repo deliveries;

	@Inject
	User.Repo userRepo;

	@TestHTTPResource("/git")
	URL gitBase;

	@Test
	void pushToPublicRepoEmitsPushAndEnqueuesDelivery() throws Exception
	{
		User owner = persistUser();
		Repository repo = service.create(owner, "fedpush-" + unique(), Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));
		String follower = "https://peer.test/ap/users/frank-" + unique();
		String followerInbox = follower + "/inbox";
		addFollower(repo, follower, followerInbox);

		pushNewCommit(owner, repo, "feature.txt");

		assertTrue(outbox.findByActor(FederationKey.ActorType.REPOSITORY, repo.id.toString()).stream()
			.anyMatch(a -> a.payload.contains("\"type\":\"Push\"")), "a Push must be in the outbox");
		assertTrue(deliveries.findDue(Instant.now()).stream()
			.anyMatch(t -> t.targetInbox.equals(followerInbox) && t.payload.contains("\"type\":\"Push\"")),
			"a Push delivery to the follower must be enqueued");
	}

	@Test
	void pushToPrivateRepoEmitsNothing() throws Exception
	{
		User owner = persistUser();
		Repository repo = service.create(owner, "fedpriv-" + unique(), Repository.Visibility.PRIVATE, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));

		pushNewCommit(owner, repo, "secret.txt");

		assertFalse(outbox.findByActor(FederationKey.ActorType.REPOSITORY, repo.id.toString()).stream()
			.anyMatch(a -> a.payload.contains("\"type\":\"Push\"")), "private repo must not federate a push");
	}

	@Test
	void unreachableFollowerDoesNotFailPush() throws Exception
	{
		User owner = persistUser();
		Repository repo = service.create(owner, "fedunreach-" + unique(), Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));
		// follower recorded but NOT in the remote-actor cache → inbox resolution will fail.
		addFollowerWithoutCache(repo, "https://peer.test/ap/users/ghost-" + unique());

		// push must still succeed and the Push must still be recorded in the outbox.
		pushNewCommit(owner, repo, "feature.txt");

		assertTrue(outbox.findByActor(FederationKey.ActorType.REPOSITORY, repo.id.toString()).stream()
			.anyMatch(a -> a.payload.contains("\"type\":\"Push\"")));
	}

	private void pushNewCommit(User owner, Repository repo, String fileName) throws Exception
	{
		String token = createToken(owner);
		Path work = Files.createTempDirectory("fedpush");
		try (Git git = Git.cloneRepository()
			.setURI(gitBase + "/" + owner.username + "/" + repo.name + ".git")
			.setDirectory(work.toFile())
			.setCredentialsProvider(new UsernamePasswordCredentialsProvider(owner.username, token))
			.call())
		{
			Files.writeString(work.resolve(fileName), "content\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("add " + fileName).setSign(false)
				.setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
			git.push()
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(owner.username, token))
				.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
				.call();
		}
	}

	@Transactional
	void addFollower(Repository repo, String followerActorId, String inbox)
	{
		Repository managed = service.find(repo.owner.username, repo.name).orElseThrow();
		RepositoryFollower follower = new RepositoryFollower();
		follower.repository = managed;
		follower.followerActorId = followerActorId;
		follower.persist();

		RemoteActor actor = new RemoteActor();
		actor.actorId = followerActorId;
		actor.inbox = inbox;
		actor.publicKeyPem = "-----BEGIN PUBLIC KEY-----\nstub\n-----END PUBLIC KEY-----";
		actor.fetchedAt = Instant.now();
		actor.persist();
	}

	@Transactional
	void addFollowerWithoutCache(Repository repo, String followerActorId)
	{
		Repository managed = service.find(repo.owner.username, repo.name).orElseThrow();
		RepositoryFollower follower = new RepositoryFollower();
		follower.repository = managed;
		follower.followerActorId = followerActorId;
		follower.persist();
	}

	@Transactional
	String createToken(User user)
	{
		return tokenService.create(user, "test").plaintext();
	}

	@Transactional
	User persistUser()
	{
		String name = "fedpush-" + UUID.randomUUID();
		User user = new User();
		user.oidcSub = "sub-" + name;
		user.username = name;
		user.persist();
		return user;
	}

	private static String unique()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
