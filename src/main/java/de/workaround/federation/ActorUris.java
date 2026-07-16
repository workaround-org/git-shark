package de.workaround.federation;

import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Builds the absolute, stable actor and collection URLs from the configured public base URL.
 * All federation URLs live under {@code /ap}; the public key fragment is {@code #main-key}.
 */
@ApplicationScoped
public class ActorUris
{
	public static final String KEY_FRAGMENT = "#main-key";

	@Inject
	FederationConfig config;

	public String repository(Repository repo)
	{
		return repository(repo.ownerHandle(), repo.name);
	}

	public String repository(String owner, String name)
	{
		return config.baseUrl() + "/ap/repos/" + owner + "/" + name;
	}

	public String person(User user)
	{
		return person(user.username);
	}

	public String person(String username)
	{
		return config.baseUrl() + "/ap/users/" + username;
	}

	public String instance()
	{
		return config.baseUrl() + "/ap/instance";
	}

	public String inbox(String actorId)
	{
		return actorId + "/inbox";
	}

	public String outbox(String actorId)
	{
		return actorId + "/outbox";
	}

	public String followers(String actorId)
	{
		return actorId + "/followers";
	}

	public String repositories(String actorId)
	{
		return actorId + "/repositories";
	}

	public String keyId(String actorId)
	{
		return actorId + KEY_FRAGMENT;
	}

}
