package de.workaround.federation;

import java.util.List;
import java.util.UUID;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.model.FederationKey;
import de.workaround.model.ReceivedPush;
import de.workaround.model.RemoteActor;
import de.workaround.model.RemoteFollow;
import de.workaround.model.RemoteUserFollow;
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
	private static final Logger LOG = Logger.getLogger(RemoteFollowService.class);

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
	RemoteUserFollow.Repo userFollows;

	@Inject
	RemoteRepositoryDirectory directory;

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
		return followRepositoryActor(user, resolveActorId(input), null);
	}

	/**
	 * Records a follow of one repository actor and enqueues the signed {@code Follow}. Idempotent per
	 * (user, actor). {@code viaUserActorId} tags follows created by fanning out a followed user's
	 * repositories collection; pass {@code null} for a direct repository follow.
	 */
	private RemoteFollow followRepositoryActor(User user, String actorId, String viaUserActorId)
	{
		RemoteFollow existing = follows.findByUserAndRemoteActorId(user, actorId).orElse(null);
		if (existing != null)
		{
			return existing;
		}
		RemoteActor remote = client.fetchActor(actorId)
			.orElseThrow(() -> new RemoteFollowException("Could not resolve remote repository: " + actorId));

		keyService.getOrCreate(FederationKey.ActorType.PERSON, user.id.toString());
		String personId = uris.person(user);
		String activityId = personId + "/activities/" + UUID.randomUUID();
		ObjectNode followActivity = documents.follow(personId, actorId, activityId);

		RemoteFollow follow = new RemoteFollow();
		follow.user = users.findById(user.id); // re-attach — callers may pass a detached user
		follow.remoteActorId = actorId;
		follow.followActivityId = activityId;
		follow.viaUserActorId = viaUserActorId;
		follow.persist();

		delivery.enqueue(remote.inbox, FederationKey.ActorType.PERSON, user.id.toString(),
			uris.keyId(personId), bytes(followActivity));
		return follow;
	}

	/**
	 * Follows a remote {@code Person} named by {@code input} (actor URL or {@code username@host}
	 * handle): fans out to a repository follow per public repository the remote advertises. Idempotent
	 * per (user, remote person). The repository set is a snapshot at follow time.
	 */
	@Transactional
	public RemoteUserFollow followUser(User user, String input)
	{
		requireOperational();
		if (user.username == null)
		{
			throw new RemoteFollowException("Choose a username before following remote users");
		}
		String actorId = resolveActorId(input);
		User attached = users.findById(user.id);
		RemoteUserFollow existing = userFollows.findByUserAndRemoteUserActorId(attached, actorId).orElse(null);
		if (existing != null)
		{
			return existing;
		}
		RemoteUserFollow userFollow = new RemoteUserFollow();
		userFollow.user = attached;
		userFollow.remoteUserActorId = actorId;
		userFollow.handle = displayHandle(input, actorId);
		userFollow.persist();

		for (String repoActorId : directory.repositoriesOf(actorId))
		{
			try
			{
				followRepositoryActor(user, repoActorId, actorId);
			}
			catch (RemoteFollowException e)
			{
				// One unreachable repository must not abort the whole user follow.
				LOG.debugf("Skipping unresolvable repository %s of %s: %s", repoActorId, actorId, e.getMessage());
			}
		}
		return userFollow;
	}

	/** Unfollows a remote user: undoes every repository follow fanned out from it, then removes it. */
	@Transactional
	public void unfollowUser(User user, UUID userFollowId)
	{
		RemoteUserFollow userFollow = userFollows.findById(userFollowId);
		if (userFollow == null || !userFollow.user.id.equals(user.id))
		{
			return; // not this user's follow — nothing to do
		}
		for (RemoteFollow follow : follows.findByUserAndViaUserActorId(users.findById(user.id),
			userFollow.remoteUserActorId))
		{
			unfollow(user, follow.id);
		}
		userFollows.deleteById(userFollow.id);
	}

	/** The handle to display: the entered {@code username@host} form, else the resolved actor id. */
	private String displayHandle(String input, String actorId)
	{
		String trimmed = input == null ? "" : input.trim();
		boolean isHandle = !trimmed.startsWith("http://") && !trimmed.startsWith("https://")
			&& trimmed.contains("@");
		return isHandle ? trimmed : actorId;
	}

	@Transactional
	public List<RemoteUserFollow> listUsers(User user)
	{
		return userFollows.findByUser(users.findById(user.id));
	}

	/** The repository follows fanned out from a followed user, for grouped display. */
	@Transactional
	public List<RemoteFollow> repositoriesOfFollowedUser(User user, String remoteUserActorId)
	{
		return follows.findByUserAndViaUserActorId(users.findById(user.id), remoteUserActorId);
	}

	/** Repository follows the user created directly (not via a followed user). */
	@Transactional
	public List<RemoteFollow> standaloneRepositoryFollows(User user)
	{
		return follows.findStandaloneByUser(users.findById(user.id));
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
