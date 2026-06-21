package de.workaround.federation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.FederationKey;
import de.workaround.model.OutboxActivity;
import de.workaround.model.Repository;
import de.workaround.model.RepositoryFollower;
import de.workaround.model.User;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Builds and fans out a ForgeFed {@code Push} activity when commits are received on a PUBLIC
 * federated repository. Invoked from a JGit post-receive hook on both transports, so it runs on a
 * Git worker thread without a CDI request context — it activates one, like {@code SshGitBridge}.
 */
@ApplicationScoped
public class FederationPushService
{
	private static final Logger LOG = Logger.getLogger(FederationPushService.class);

	private static final int MAX_COMMITS = 50;

	@Inject
	FederationConfig config;

	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo users;

	@Inject
	RepositoryFollower.Repo followers;

	@Inject
	OutboxActivity.Repo outbox;

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

	/** Entry point from the transports' post-receive hooks. Never throws into the Git path. */
	public void onPush(String ownerName, String repoName, UUID pusherUserId,
		org.eclipse.jgit.lib.Repository db, Collection<ReceiveCommand> commands)
	{
		if (!config.operational())
		{
			return;
		}
		var requestContext = Arc.container().requestContext();
		boolean activated = !requestContext.isActive();
		if (activated)
		{
			requestContext.activate();
		}
		try
		{
			publish(ownerName, repoName, pusherUserId, db, commands);
		}
		catch (RuntimeException e)
		{
			LOG.warnf(e, "Failed to publish push activity for %s/%s", ownerName, repoName);
		}
		finally
		{
			if (activated)
			{
				requestContext.terminate();
			}
		}
	}

	@Transactional
	void publish(String ownerName, String repoName, UUID pusherUserId,
		org.eclipse.jgit.lib.Repository db, Collection<ReceiveCommand> commands)
	{
		Repository repo = repositories.find(ownerName, repoName).orElse(null);
		if (repo == null || repo.visibility != Repository.Visibility.PUBLIC)
		{
			return;
		}
		keyService.getOrCreate(FederationKey.ActorType.REPOSITORY, repo.id.toString());
		String repoActorId = uris.repository(repo);
		String pusherActorId = pusherActor(pusherUserId);
		List<RepositoryFollower> recipients = followers.findByRepository(repo);

		for (ReceiveCommand command : commands)
		{
			if (command.getResult() != ReceiveCommand.Result.OK
				|| !command.getRefName().startsWith("refs/heads/")
				|| command.getType() == ReceiveCommand.Type.DELETE)
			{
				continue;
			}
			String oldId = command.getOldId().name();
			String newId = command.getNewId().name();
			List<String> commitIds = newCommits(db, command.getOldId(), command.getNewId());

			ObjectNode push = documents.pushActivity(repoActorId, pusherActorId, command.getRefName(),
				oldId, newId, commitIds);
			String activityId = push.path("id").asText();
			byte[] payload = bytes(push);

			OutboxActivity record = new OutboxActivity();
			record.actorType = FederationKey.ActorType.REPOSITORY;
			record.actorRef = repo.id.toString();
			record.activityId = activityId;
			record.payload = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
			record.persist();

			for (RepositoryFollower follower : recipients)
			{
				client.fetchActor(follower.followerActorId).ifPresent(remote ->
					delivery.enqueue(remote.inbox, FederationKey.ActorType.REPOSITORY, repo.id.toString(),
						uris.keyId(repoActorId), payload));
			}
			LOG.infof("Published Push %s for %s to %d follower(s)", activityId, repoActorId, recipients.size());
		}
	}

	private String pusherActor(UUID pusherUserId)
	{
		if (pusherUserId == null)
		{
			return null;
		}
		User user = users.findById(pusherUserId);
		return user == null ? null : uris.person(user);
	}

	private static List<String> newCommits(org.eclipse.jgit.lib.Repository db, ObjectId oldId, ObjectId newId)
	{
		List<String> ids = new ArrayList<>();
		try (RevWalk walk = new RevWalk(db))
		{
			walk.markStart(walk.parseCommit(newId));
			if (oldId != null && !oldId.equals(ObjectId.zeroId()))
			{
				walk.markUninteresting(walk.parseCommit(oldId));
			}
			for (RevCommit commit : walk)
			{
				ids.add(commit.name());
				if (ids.size() >= MAX_COMMITS)
				{
					break;
				}
			}
		}
		catch (Exception e)
		{
			// commit enumeration is best-effort metadata; the Push still goes out with old/new ids
		}
		return ids;
	}

	private byte[] bytes(ObjectNode node)
	{
		try
		{
			return mapper.writeValueAsBytes(node);
		}
		catch (JsonProcessingException e)
		{
			throw new IllegalStateException("Failed to serialize Push activity", e);
		}
	}

}
