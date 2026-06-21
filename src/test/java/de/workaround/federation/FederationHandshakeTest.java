package de.workaround.federation;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 6: the cross-instance handshake driven through the real verification path. A "remote"
 * peer's keypair is pre-seeded into the actor cache (so key resolution is a cache hit rather than a
 * socket); a signed Follow is fed to {@link InboxService}, which verifies the HTTP Signature,
 * deduplicates, records the follower, and enqueues an Accept. Replays are no-ops; tampering is 401.
 *
 * <p>A fully networked two-host test (real HTTPS between two instances) is a manual/staging step —
 * see tasks 6.3/6.4 — because the SSRF guard intentionally blocks loopback and requires HTTPS.
 */
@QuarkusTest
class FederationHandshakeTest
{
	@Inject
	InboxService inboxService;

	@Inject
	GitRepositoryService service;

	@Inject
	ActorUris uris;

	@Inject
	RepositoryFollower.Repo followers;

	@Inject
	DeliveryTask.Repo deliveries;

	@Inject
	User.Repo userRepo;

	@Inject
	ObjectMapper mapper;

	private final KeyPair remoteKeys = rsaKeyPair();

	@Test
	void signedFollowIsVerifiedRecordedAndAccepted()
	{
		Repository repo = freshPublicRepo("hs-bob");
		String remoteActor = "https://peer.test/ap/users/frank-" + unique();
		String keyId = remoteActor + "#main-key";
		seedRemoteActor(remoteActor, remoteActor + "/inbox");

		String repoActor = uris.repository(repo);
		ObjectNode follow = follow(remoteActor, repoActor);
		byte[] body = bytes(follow);
		String path = URI.create(repoActor).getPath() + "/inbox";

		Map<String, String> signed = HttpSignatures.signPost(
			URI.create(repoActor + "/inbox"), body, keyId, remoteKeys.getPrivate());

		int status = inboxService.receive(body, lookup(signed), "POST", path);

		assertEquals(InboxService.ACCEPTED, status);
		assertTrue(hasFollower(repo, remoteActor), "follower must be recorded");
		assertTrue(deliveries.findDue(Instant.now()).stream()
			.anyMatch(t -> t.targetInbox.equals(remoteActor + "/inbox") && t.payload.contains("\"Accept\"")),
			"an Accept must be enqueued to the follower");
	}

	@Test
	void replayedFollowIsIdempotent()
	{
		Repository repo = freshPublicRepo("hs-carol");
		String remoteActor = "https://peer.test/ap/users/gwen-" + unique();
		String keyId = remoteActor + "#main-key";
		seedRemoteActor(remoteActor, remoteActor + "/inbox");

		String repoActor = uris.repository(repo);
		byte[] body = bytes(follow(remoteActor, repoActor));
		String path = URI.create(repoActor).getPath() + "/inbox";
		Map<String, String> signed = HttpSignatures.signPost(
			URI.create(repoActor + "/inbox"), body, keyId, remoteKeys.getPrivate());

		assertEquals(InboxService.ACCEPTED, inboxService.receive(body, lookup(signed), "POST", path));
		assertEquals(InboxService.ACCEPTED, inboxService.receive(body, lookup(signed), "POST", path));

		assertEquals(1, followerCount(repo, remoteActor), "replay must not double-record the follower");
	}

	@Test
	void tamperedBodyIsRejected()
	{
		Repository repo = freshPublicRepo("hs-dave");
		String remoteActor = "https://peer.test/ap/users/mallory-" + unique();
		String keyId = remoteActor + "#main-key";
		seedRemoteActor(remoteActor, remoteActor + "/inbox");

		String repoActor = uris.repository(repo);
		byte[] body = bytes(follow(remoteActor, repoActor));
		String path = URI.create(repoActor).getPath() + "/inbox";
		Map<String, String> signed = HttpSignatures.signPost(
			URI.create(repoActor + "/inbox"), body, keyId, remoteKeys.getPrivate());

		byte[] tampered = bytes(follow(remoteActor, repoActor)); // different activity id → digest mismatch
		assertEquals(InboxService.UNAUTHORIZED, inboxService.receive(tampered, lookup(signed), "POST", path));
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

	@Transactional
	boolean hasFollower(Repository repo, String actorId)
	{
		Repository managed = service.find(repo.owner.username, repo.name).orElseThrow();
		return followers.findByRepositoryAndFollowerActorId(managed, actorId).isPresent();
	}

	@Transactional
	long followerCount(Repository repo, String actorId)
	{
		Repository managed = service.find(repo.owner.username, repo.name).orElseThrow();
		return followers.findByRepository(managed).stream().filter(f -> f.followerActorId.equals(actorId)).count();
	}

	@Transactional
	void seedRemoteActor(String actorId, String inbox)
	{
		RemoteActor actor = new RemoteActor();
		actor.actorId = actorId;
		actor.inbox = inbox;
		actor.publicKeyPem = pem(remoteKeys);
		actor.fetchedAt = Instant.now();
		actor.persist();
	}

	@Transactional
	Repository freshPublicRepo(String ownerPrefix)
	{
		User owner = userRepo.findByOidcSubOptional(ownerPrefix + "-" + unique()).orElseGet(() -> {
			User user = new User();
			user.oidcSub = ownerPrefix + "-" + unique();
			user.username = ownerPrefix + "-" + unique();
			user.persist();
			return user;
		});
		return service.create(owner, "repo-" + unique(), Repository.Visibility.PUBLIC, null);
	}

	private byte[] bytes(ObjectNode node)
	{
		try
		{
			return mapper.writeValueAsBytes(node);
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static Function<String, String> lookup(Map<String, String> headers)
	{
		Map<String, String> lower = new HashMap<>();
		headers.forEach((k, v) -> lower.put(k.toLowerCase(Locale.ROOT), v));
		return name -> lower.get(name.toLowerCase(Locale.ROOT));
	}

	private static String pem(KeyPair pair)
	{
		String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.UTF_8))
			.encodeToString(pair.getPublic().getEncoded());
		return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
	}

	private static KeyPair rsaKeyPair()
	{
		try
		{
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return generator.generateKeyPair();
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

	private static String unique()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
