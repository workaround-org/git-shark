package de.workaround.federation;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.model.FederationKey;
import de.workaround.model.ReceivedPush;
import de.workaround.model.RemoteActor;
import de.workaround.model.RemoteFollow;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Outbound following of remote repositories: resolves a handle ({@code owner/name@host}) or actor
 * URL, sends a {@code Follow} signed as the user's Person actor via the delivery queue, and records
 * it as {@code PENDING} until the remote's {@code Accept} confirms it. Unfollowing sends an
 * {@code Undo(Follow)} and removes the record.
 */
@ApplicationScoped
public class RemoteFollowService
{
	/** A follow/unfollow request that cannot be fulfilled; the message is safe to show the user. */
	public static class RemoteFollowException extends RuntimeException
	{
		public RemoteFollowException(String message)
		{
			super(message);
		}
	}

	@Inject
	FederationConfig config;

	@Inject
	ActivityPubClient client;

	@Inject
	ActorKeyService keyService;

	@Inject
	ActorUris uris;

	@Inject
	ActorDocuments documents;

	@Inject
	DeliveryService delivery;

	private static final int FEED_LIMIT = 50;

	@Inject
	RemoteFollow.Repo follows;

	@Inject
	ReceivedPush.Repo pushes;

	@Inject
	User.Repo users;

	@Inject
	ObjectMapper mapper;

	/**
	 * Follows the remote repository named by {@code input} — an actor URL or a
	 * {@code owner/name@host} handle — as the given user. Idempotent per (user, remote actor).
	 */
	@Transactional
	public RemoteFollow follow(User user, String input)
	{
		requireOperational();
		if (user.username == null)
		{
			throw new RemoteFollowException("Choose a username before following remote repositories");
		}
		String actorId = resolveActorId(input);
		RemoteFollow existing = follows.findByUserAndRemoteActorId(user, actorId).orElse(null);
		if (existing != null)
		{
			return existing;
		}
		RemoteActor remote = client.fetchActor(actorId)
			.orElseThrow(() -> new RemoteFollowException("Could not resolve remote repository: " + input));

		keyService.getOrCreate(FederationKey.ActorType.PERSON, user.id.toString());
		String personId = uris.person(user);
		String activityId = personId + "/activities/" + UUID.randomUUID();
		ObjectNode followActivity = documents.follow(personId, actorId, activityId);

		RemoteFollow follow = new RemoteFollow();
		follow.user = users.findById(user.id); // re-attach — callers may pass a detached user
		follow.remoteActorId = actorId;
		follow.followActivityId = activityId;
		follow.persist();

		delivery.enqueue(remote.inbox, FederationKey.ActorType.PERSON, user.id.toString(),
			uris.keyId(personId), bytes(followActivity));
		return follow;
	}

	/** Sends an {@code Undo(Follow)} to the remote and removes the user's follow record. */
	@Transactional
	public void unfollow(User user, UUID followId)
	{
		RemoteFollow follow = follows.findById(followId);
		if (follow == null || !follow.user.id.equals(user.id))
		{
			return; // not this user's follow — nothing to do
		}
		String personId = uris.person(user);
		client.fetchActor(follow.remoteActorId).ifPresent(remote ->
		{
			ObjectNode original = documents.follow(personId, follow.remoteActorId, follow.followActivityId);
			ObjectNode undo = documents.undo(personId, original);
			delivery.enqueue(remote.inbox, FederationKey.ActorType.PERSON, user.id.toString(),
				uris.keyId(personId), bytes(undo));
		});
		follows.deleteById(follow.id);
	}

	@Transactional
	public List<RemoteFollow> list(User user)
	{
		return follows.findByUser(user);
	}

	/** Recent {@code Push} activities of the repositories the user follows, newest first. */
	@Transactional
	public List<ReceivedPush> recentPushes(User user)
	{
		List<String> actorIds = follows.findByUser(user).stream().map(f -> f.remoteActorId).toList();
		if (actorIds.isEmpty())
		{
			return List.of();
		}
		List<ReceivedPush> feed = pushes.findByRemoteActorIds(actorIds);
		return feed.size() > FEED_LIMIT ? feed.subList(0, FEED_LIMIT) : feed;
	}

	/** Resolves the input to a remote actor id: pass URLs through, WebFinger-resolve handles. */
	private String resolveActorId(String input)
	{
		String trimmed = input == null ? "" : input.trim();
		if (trimmed.isEmpty())
		{
			throw new RemoteFollowException("Enter a repository handle (owner/name@host) or actor URL");
		}
		if (trimmed.startsWith("https://") || trimmed.startsWith("http://"))
		{
			return trimmed;
		}
		int at = trimmed.lastIndexOf('@');
		if (at <= 0 || at == trimmed.length() - 1)
		{
			throw new RemoteFollowException("Not a valid handle — expected owner/name@host");
		}
		return client.resolveWebFinger(trimmed.substring(0, at), trimmed.substring(at + 1))
			.orElseThrow(() -> new RemoteFollowException("Could not resolve handle: " + trimmed));
	}

	private void requireOperational()
	{
		if (!config.operational())
		{
			throw new RemoteFollowException("Federation is not enabled on this instance");
		}
	}

	private byte[] bytes(ObjectNode node)
	{
		try
		{
			return mapper.writeValueAsBytes(node);
		}
		catch (JsonProcessingException e)
		{
			throw new IllegalStateException("Failed to serialize activity", e);
		}
	}

}
