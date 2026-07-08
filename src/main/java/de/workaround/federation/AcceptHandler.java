package de.workaround.federation;

import com.fasterxml.jackson.databind.JsonNode;

import de.workaround.model.RemoteFollow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Handles an inbound {@code Accept} of a {@code Follow} we sent: marks the matching outbound
 * follow {@code ACCEPTED}, but only when the accepting actor is the one that was followed.
 */
@ApplicationScoped
public class AcceptHandler
{
	private static final Logger LOG = Logger.getLogger(AcceptHandler.class);

	@Inject
	RemoteFollow.Repo follows;

	@Transactional
	public void handle(JsonNode accept)
	{
		String followActivityId = LocalActors.idOf(accept.path("object"));
		String acceptingActorId = LocalActors.idOf(accept.path("actor"));
		if (followActivityId == null || acceptingActorId == null)
		{
			return;
		}
		follows.findByFollowActivityId(followActivityId).ifPresentOrElse(follow ->
		{
			if (!follow.remoteActorId.equals(acceptingActorId))
			{
				LOG.warnf("Ignoring Accept of %s from unexpected actor %s", followActivityId, acceptingActorId);
				return;
			}
			follow.state = RemoteFollow.State.ACCEPTED;
		}, () -> LOG.debugf("Accept references no known outbound follow: %s", followActivityId));
	}

}
