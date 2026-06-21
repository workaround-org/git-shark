package de.workaround.federation;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Maps inbound actor-id URLs back to local entities and extracts ids from activity nodes. */
@ApplicationScoped
public class LocalActors
{
	@Inject
	FederationConfig config;

	@Inject
	GitRepositoryService repositories;

	/** Resolves a local PUBLIC repository from its actor id, or empty if not one of ours. */
	public Optional<Repository> repositoryFromActorId(String actorId)
	{
		if (actorId == null)
		{
			return Optional.empty();
		}
		String prefix = config.baseUrl() + "/ap/repos/";
		if (!actorId.startsWith(prefix))
		{
			return Optional.empty();
		}
		String[] parts = actorId.substring(prefix.length()).split("/");
		if (parts.length != 2)
		{
			return Optional.empty();
		}
		return repositories.find(parts[0], parts[1])
			.filter(repo -> repo.visibility == Repository.Visibility.PUBLIC);
	}

	/** Extracts the id of an activity property that may be a string id or an embedded object. */
	public static String idOf(JsonNode node)
	{
		if (node == null || node.isMissingNode() || node.isNull())
		{
			return null;
		}
		if (node.isTextual())
		{
			return node.asText();
		}
		if (node.isObject())
		{
			return node.path("id").asText(null);
		}
		return null;
	}

}
