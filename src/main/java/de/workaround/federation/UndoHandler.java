package de.workaround.federation;

import com.fasterxml.jackson.databind.JsonNode;

import de.workaround.model.Repository;
import de.workaround.model.RepositoryFollower;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Handles an inbound {@code Undo} of a {@code Follow}: removes the actor from the repository's
 * followers so it no longer receives activity deliveries.
 */
@ApplicationScoped
public class UndoHandler
{
	@Inject
	LocalActors local;

	@Inject
	RepositoryFollower.Repo followers;

	@Transactional
	public void handle(JsonNode undo)
	{
		JsonNode object = undo.path("object");
		if (!"Follow".equals(object.path("type").asText("")))
		{
			return; // only Undo(Follow) is handled
		}
		String followerActorId = LocalActors.idOf(undo.path("actor"));
		Repository repo = local.repositoryFromActorId(LocalActors.idOf(object.path("object"))).orElse(null);
		if (repo == null || followerActorId == null)
		{
			return;
		}
		followers.findByRepositoryAndFollowerActorId(repo, followerActorId)
			.ifPresent(follower -> followers.deleteById(follower.id));
	}

}
