package de.workaround.federation;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.FederationKey;
import de.workaround.model.OutboxActivity;
import de.workaround.model.Repository;
import de.workaround.model.RepositoryFollower;
import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

/**
 * Serves the federation read surface under {@code /ap}: ForgeFed/ActivityPub actor documents and
 * their {@code outbox} / {@code followers} collections. All endpoints 404 when federation is not
 * operational, and only PUBLIC repositories are exposed.
 */
@Path("/ap")
@Produces({ ActivityPubMedia.ACTIVITY_JSON, ActivityPubMedia.LD_JSON })
public class ActivityPubResource
{
	@Inject
	FederationConfig config;

	@Inject
	ActorDocuments documents;

	@Inject
	ActorUris uris;

	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo users;

	@Inject
	RepositoryFollower.Repo followers;

	@Inject
	OutboxActivity.Repo outbox;

	@Inject
	ObjectMapper mapper;

	@GET
	@Path("instance")
	public Response instanceActor()
	{
		requireOperational();
		return json(documents.instanceActor());
	}

	@GET
	@Path("repos/{owner}/{name}")
	public Response repositoryActor(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		return json(documents.repositoryActor(requirePublicRepo(owner, name)));
	}

	@GET
	@Path("repos/{owner}/{name}/outbox")
	public Response repositoryOutbox(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requirePublicRepo(owner, name);
		String id = uris.outbox(uris.repository(repo));
		return json(documents.orderedCollection(id, payloads(FederationKey.ActorType.REPOSITORY, repo.id.toString())));
	}

	@GET
	@Path("repos/{owner}/{name}/followers")
	public Response repositoryFollowers(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requirePublicRepo(owner, name);
		String id = uris.followers(uris.repository(repo));
		List<Object> items = new ArrayList<>(
			followers.findByRepository(repo).stream().map(f -> (Object) f.followerActorId).toList());
		return json(documents.orderedCollection(id, items));
	}

	@GET
	@Path("users/{username}")
	public Response personActor(@PathParam("username") String username)
	{
		return json(documents.personActor(requireUser(username)));
	}

	@GET
	@Path("users/{username}/outbox")
	public Response personOutbox(@PathParam("username") String username)
	{
		User user = requireUser(username);
		String id = uris.outbox(uris.person(user));
		return json(documents.orderedCollection(id, payloads(FederationKey.ActorType.PERSON, user.id.toString())));
	}

	@GET
	@Path("users/{username}/repositories")
	public Response personRepositories(@PathParam("username") String username)
	{
		User user = requireUser(username);
		String id = uris.repositories(uris.person(user));
		List<Object> items = new ArrayList<>(repositories.listOwnedBy(user).stream()
			.filter(repo -> repo.visibility == Repository.Visibility.PUBLIC)
			.map(repo -> (Object) uris.repository(repo))
			.toList());
		return json(documents.orderedCollection(id, items));
	}

	private List<Object> payloads(FederationKey.ActorType type, String ref)
	{
		List<Object> items = new ArrayList<>();
		for (OutboxActivity activity : outbox.findByActor(type, ref))
		{
			try
			{
				items.add(mapper.readTree(activity.payload));
			}
			catch (JsonProcessingException e)
			{
				items.add(activity.activityId);
			}
		}
		return items;
	}

	private Repository requirePublicRepo(String owner, String name)
	{
		requireOperational();
		return repositories.find(owner, name)
			.filter(repo -> repo.visibility == Repository.Visibility.PUBLIC)
			.orElseThrow(NotFoundException::new);
	}

	private User requireUser(String username)
	{
		requireOperational();
		return users.findByUsername(username).orElseThrow(NotFoundException::new);
	}

	private void requireOperational()
	{
		if (!config.operational())
		{
			throw new NotFoundException();
		}
	}

	private Response json(JsonNode node)
	{
		try
		{
			return Response.ok(mapper.writeValueAsString(node)).type(ActivityPubMedia.ACTIVITY_JSON).build();
		}
		catch (JsonProcessingException e)
		{
			throw new IllegalStateException("Failed to serialize federation document", e);
		}
	}

}
