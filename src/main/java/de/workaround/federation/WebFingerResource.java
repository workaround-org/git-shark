package de.workaround.federation;

import java.net.URI;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Response;

/**
 * WebFinger actor discovery. Resolves {@code acct:{username}@{host}} to a Person actor and
 * {@code acct:{owner}/{name}@{host}} to a (public) Repository actor, returning a JRD whose
 * {@code self} link points at the actor id.
 */
@Path("/.well-known/webfinger")
public class WebFingerResource
{
	@Inject
	FederationConfig config;

	@Inject
	ActorUris uris;

	@Inject
	ActorDocuments documents;

	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo users;

	@Inject
	ObjectMapper mapper;

	@GET
	@Produces(ActivityPubMedia.JRD_JSON)
	public Response resolve(@QueryParam("resource") String resource)
	{
		if (!config.operational() || resource == null || !resource.startsWith("acct:"))
		{
			throw new NotFoundException();
		}
		String acct = resource.substring("acct:".length());
		int at = acct.lastIndexOf('@');
		if (at < 0)
		{
			throw new NotFoundException();
		}
		String identifier = acct.substring(0, at);
		String host = acct.substring(at + 1);
		if (!host.equalsIgnoreCase(baseHost()))
		{
			throw new NotFoundException();
		}

		String actorId;
		if (identifier.contains("/"))
		{
			int slash = identifier.indexOf('/');
			Repository repo = repositories.find(identifier.substring(0, slash), identifier.substring(slash + 1))
				.filter(r -> r.visibility == Repository.Visibility.PUBLIC)
				.orElseThrow(NotFoundException::new);
			actorId = uris.repository(repo);
		}
		else
		{
			User user = users.findByUsername(identifier).orElseThrow(NotFoundException::new);
			actorId = uris.person(user);
		}

		ObjectNode jrd = documents.webfinger(resource, actorId);
		try
		{
			return Response.ok(mapper.writeValueAsString(jrd)).type(ActivityPubMedia.JRD_JSON).build();
		}
		catch (JsonProcessingException e)
		{
			throw new IllegalStateException("Failed to serialize WebFinger document", e);
		}
	}

	private String baseHost()
	{
		return URI.create(config.baseUrl()).getHost();
	}

}
