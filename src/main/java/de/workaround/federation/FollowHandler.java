package de.workaround.federation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.model.FederationKey;
import de.workaround.model.Repository;
import de.workaround.model.RepositoryFollower;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Handles an inbound {@code Follow} of a repository actor: records the follower (idempotently) and
 * enqueues an {@code Accept} back to the follower's inbox, signed as the repository actor.
 */
@ApplicationScoped
public class FollowHandler
{
	@Inject
	LocalActors local;

	@Inject
	RepositoryFollower.Repo followers;

	@Inject
	ActorKeyService keyService;

	@Inject
	ActorUris uris;

	@Inject
	ActorDocuments documents;

	@Inject
	ActivityPubClient client;

	@Inject
	DeliveryService delivery;

	@Inject
	ObjectMapper mapper;

	@Transactional
	public void handle(JsonNode follow)
	{
		String followerActorId = LocalActors.idOf(follow.path("actor"));
		Repository repo = local.repositoryFromActorId(LocalActors.idOf(follow.path("object"))).orElse(null);
		if (repo == null || followerActorId == null)
		{
			return; // not a follow of one of our public repositories
		}

		if (followers.findByRepositoryAndFollowerActorId(repo, followerActorId).isEmpty())
		{
			RepositoryFollower follower = new RepositoryFollower();
			follower.repository = repo;
			follower.followerActorId = followerActorId;
			follower.persist();
		}

		keyService.getOrCreate(FederationKey.ActorType.REPOSITORY, repo.id.toString());
		String repoActorId = uris.repository(repo);
		client.fetchActor(followerActorId).ifPresent(follower ->
		{
			ObjectNode accept = documents.acceptFollow(repoActorId, follow);
			delivery.enqueue(follower.inbox, FederationKey.ActorType.REPOSITORY, repo.id.toString(),
				uris.keyId(repoActorId), bytes(accept));
		});
	}

	private byte[] bytes(ObjectNode node)
	{
		try
		{
			return mapper.writeValueAsBytes(node);
		}
		catch (JsonProcessingException e)
		{
			throw new IllegalStateException("Failed to serialize Accept activity", e);
		}
	}

}
