package de.workaround.federation;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Routes a verified, deduplicated inbound activity to the right handler. Inbound {@code Accept}
 * (acknowledging a follow we sent) confirms the outbound follow; unknown types are
 * stored-and-ignored.
 */
@ApplicationScoped
public class ActivityDispatcher
{
	private static final Logger LOG = Logger.getLogger(ActivityDispatcher.class);

	@Inject
	FollowHandler followHandler;

	@Inject
	UndoHandler undoHandler;

	@Inject
	AcceptHandler acceptHandler;

	@Inject
	PushHandler pushHandler;

	/** Called within the inbox receipt transaction, after the activity id has been recorded. */
	public void dispatch(JsonNode activity)
	{
		String type = activity.path("type").asText("");
		switch (type)
		{
			case "Follow" -> followHandler.handle(activity);
			case "Undo" -> undoHandler.handle(activity);
			case "Accept" -> acceptHandler.handle(activity);
			case "Push" -> pushHandler.handle(activity);
			default -> LOG.debugf("Ignoring unsupported activity type: %s", type);
		}
	}

}
