package de.workaround.federation;

import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Discovers the public repositories of a remote {@code Person} by reading the {@code repositories}
 * collection git-shark advertises on its Person actors (at {@code <person-actor-id>/repositories}).
 * The convention path keeps discovery to a single fetch for the git-shark&#8596;git-shark case;
 * broader ForgeFed peers that publish the link under a different path are out of scope for now.
 */
@ApplicationScoped
public class RemoteRepositoryDirectory
{
	@Inject
	ActivityPubClient client;

	/** Repository actor ids advertised by the remote Person, or empty if none/unreachable. */
	public List<String> repositoriesOf(String personActorId)
	{
		return client.fetchCollectionItemIds(personActorId + "/repositories");
	}

}
