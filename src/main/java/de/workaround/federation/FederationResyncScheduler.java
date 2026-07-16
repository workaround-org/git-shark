package de.workaround.federation;

import java.util.UUID;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Periodically re-syncs every followed remote user's repository set, so repositories a user creates
 * after being followed are eventually picked up (add-only — see {@link RemoteFollowService#resyncUser}).
 * Interval is {@code gitshark.federation.user-resync-interval}; the pass is a no-op while federation
 * is not operational.
 */
@ApplicationScoped
public class FederationResyncScheduler
{
	@Inject
	FederationConfig config;

	@Inject
	RemoteFollowService service;

	@Scheduled(every = "{gitshark.federation.user-resync-interval}",
		concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
	void resync()
	{
		if (!config.operational())
		{
			return;
		}
		for (UUID userFollowId : service.allUserFollowIds())
		{
			service.resyncUser(userFollowId);
		}
	}

}
