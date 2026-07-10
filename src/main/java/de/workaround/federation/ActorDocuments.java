package de.workaround.federation;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.model.FederationKey;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builds the compacted JSON-LD documents we serve: ForgeFed {@code Repository} / ActivityPub
 * {@code Person} / {@code Application} actors, {@code OrderedCollection}s, and WebFinger JRDs.
 * Documents are plain Jackson trees against the pinned {@code @context} — no JSON-LD processing.
 */
@ApplicationScoped
public class ActorDocuments
{
	@Inject
	ObjectMapper mapper;

	@Inject
	ActorUris uris;

	@Inject
	ActorKeyService keyService;

	public ObjectNode repositoryActor(Repository repo)
	{
		String id = uris.repository(repo);
		ObjectNode node = actorBase(id, "Repository", true);
		node.put("name", repo.ownerHandle() + "/" + repo.name);
		node.put("preferredUsername", repo.name);
		if (repo.description != null)
		{
			node.put("summary", repo.description);
		}
		node.put("inbox", uris.inbox(id));
		node.put("outbox", uris.outbox(id));
		node.put("followers", uris.followers(id));
		addPublicKey(node, id, FederationKey.ActorType.REPOSITORY, repo.id.toString());
		return node;
	}

	public ObjectNode personActor(User user)
	{
		String id = uris.person(user);
		ObjectNode node = actorBase(id, "Person", false);
		node.put("preferredUsername", user.username);
		if (user.displayName != null)
		{
			node.put("name", user.displayName);
		}
		node.put("inbox", uris.inbox(id));
		node.put("outbox", uris.outbox(id));
		addPublicKey(node, id, FederationKey.ActorType.PERSON, user.id.toString());
		return node;
	}

	public ObjectNode instanceActor()
	{
		String id = uris.instance();
		ObjectNode node = actorBase(id, "Application", false);
		node.put("preferredUsername", "instance");
		node.put("inbox", uris.inbox(id));
		addPublicKey(node, id, FederationKey.ActorType.INSTANCE, "instance");
		return node;
	}

	public ObjectNode orderedCollection(String id, List<? extends Object> items)
	{
		ObjectNode node = mapper.createObjectNode();
		node.put("@context", ActivityPubMedia.ACTIVITYSTREAMS);
		node.put("id", id);
		node.put("type", "OrderedCollection");
		node.put("totalItems", items.size());
		ArrayNode ordered = node.putArray("orderedItems");
		for (Object item : items)
		{
			if (item instanceof ObjectNode objectNode)
			{
				ordered.add(objectNode);
			}
			else
			{
				ordered.add(String.valueOf(item));
			}
		}
		return node;
	}

	/**
	 * Builds a ForgeFed {@code Push} activity for one updated ref: the repository is the actor and
	 * context, the pusher (if known) is {@code attributedTo}, and {@code object} is the collection of
	 * newly received commits. Returns the activity and its id.
	 */
	public ObjectNode pushActivity(String repoActorId, String pusherActorId, String ref, String oldId,
		String newId, List<String> commitIds)
	{
		ObjectNode node = mapper.createObjectNode();
		ArrayNode context = node.putArray("@context");
		context.add(ActivityPubMedia.ACTIVITYSTREAMS);
		context.add(ActivityPubMedia.FORGEFED);
		node.put("id", repoActorId + "/activities/" + UUID.randomUUID());
		node.put("type", "Push");
		node.put("actor", repoActorId);
		if (pusherActorId != null)
		{
			node.put("attributedTo", pusherActorId);
		}
		node.put("context", repoActorId);
		node.put("target", ref);
		node.put("oldId", oldId);
		node.put("newId", newId);
		node.put("summary", "Pushed " + commitIds.size() + " commit(s) to " + ref);
		node.putArray("to").add(uris.followers(repoActorId));

		ObjectNode commits = node.putObject("object");
		commits.put("type", "OrderedCollection");
		commits.put("totalItems", commitIds.size());
		ArrayNode items = commits.putArray("orderedItems");
		for (String sha : commitIds)
		{
			ObjectNode commit = items.addObject();
			commit.put("type", "Commit");
			commit.put("id", repoActorId + "/commits/" + sha);
			commit.put("hash", sha);
		}
		return node;
	}

	/** Builds a {@code Follow} of a remote actor, attributed to a local actor. */
	public ObjectNode follow(String actorId, String remoteActorId, String activityId)
	{
		ObjectNode node = mapper.createObjectNode();
		node.put("@context", ActivityPubMedia.ACTIVITYSTREAMS);
		node.put("id", activityId);
		node.put("type", "Follow");
		node.put("actor", actorId);
		node.put("object", remoteActorId);
		return node;
	}

	/** Builds an {@code Undo} of a previously sent activity, attributed to the same local actor. */
	public ObjectNode undo(String actorId, ObjectNode activity)
	{
		ObjectNode node = mapper.createObjectNode();
		node.put("@context", ActivityPubMedia.ACTIVITYSTREAMS);
		node.put("id", actorId + "/activities/" + UUID.randomUUID());
		node.put("type", "Undo");
		node.put("actor", actorId);
		node.set("object", activity);
		return node;
	}

	/** Builds an {@code Accept} of an inbound {@code Follow}, attributed to the followed actor. */
	public ObjectNode acceptFollow(String actorId, JsonNode follow)
	{
		ObjectNode node = mapper.createObjectNode();
		node.put("@context", ActivityPubMedia.ACTIVITYSTREAMS);
		node.put("id", actorId + "/activities/" + UUID.randomUUID());
		node.put("type", "Accept");
		node.put("actor", actorId);
		node.set("object", follow);
		return node;
	}

	public ObjectNode webfinger(String subject, String actorId)
	{
		ObjectNode node = mapper.createObjectNode();
		node.put("subject", subject);
		ArrayNode links = node.putArray("links");
		ObjectNode self = links.addObject();
		self.put("rel", "self");
		self.put("type", ActivityPubMedia.ACTIVITY_JSON);
		self.put("href", actorId);
		return node;
	}

	private ObjectNode actorBase(String id, String type, boolean forgefed)
	{
		ObjectNode node = mapper.createObjectNode();
		ArrayNode context = node.putArray("@context");
		context.add(ActivityPubMedia.ACTIVITYSTREAMS);
		context.add(ActivityPubMedia.SECURITY);
		if (forgefed)
		{
			context.add(ActivityPubMedia.FORGEFED);
		}
		node.put("id", id);
		node.put("type", type);
		return node;
	}

	private void addPublicKey(ObjectNode actor, String actorId, FederationKey.ActorType type, String ref)
	{
		FederationKey key = keyService.getOrCreate(type, ref);
		ObjectNode publicKey = actor.putObject("publicKey");
		publicKey.put("id", uris.keyId(actorId));
		publicKey.put("owner", actorId);
		publicKey.put("publicKeyPem", key.publicPem);
	}

}
