package de.workaround.federation;

import com.fasterxml.jackson.databind.JsonNode;

import de.workaround.model.ReceivedPush;
import de.workaround.model.RemoteFollow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Handles an inbound {@code Push} from a remote repository: stored for the follower-side feed, but
 * only while at least one local user follows the sending actor — anything else is dropped.
 */
@ApplicationScoped
public class PushHandler
{
	@Inject
	RemoteFollow.Repo follows;

	@Inject
	ReceivedPush.Repo pushes;

	@Transactional
	public void handle(JsonNode push)
	{
		String actorId = LocalActors.idOf(push.path("actor"));
		String activityId = push.path("id").asText(null);
		if (actorId == null || activityId == null)
		{
			return;
		}
		if (follows.findByRemoteActorId(actorId).isEmpty())
		{
			return; // nobody follows this repository — not our feed
		}
		if (pushes.findByActivityId(activityId).isPresent())
		{
			return; // redelivered — already in the feed
		}
		ReceivedPush received = new ReceivedPush();
		received.remoteActorId = actorId;
		received.activityId = activityId;
		received.summary = push.path("summary").asText(null);
		received.target = push.path("target").asText(null);
		received.payload = push.toString();
		received.persist();
	}

}
