package de.workaround.federation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import de.workaround.model.DeliveryTask;
import de.workaround.model.FederationKey;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

/**
 * Persisted outbound delivery queue. Activities are enqueued inside the triggering transaction and
 * drained by a scheduled worker that signs and POSTs them, retrying failures with exponential
 * backoff up to a configured maximum, after which a task is dead-lettered ({@code FAILED}).
 */
@ApplicationScoped
public class DeliveryService
{
	private static final Logger LOG = Logger.getLogger(DeliveryService.class);

	@Inject
	DeliveryTask.Repo tasks;

	@Inject
	FederationKey.Repo keys;

	@Inject
	ActivityPubClient client;

	@Inject
	FederationConfig config;

	/** Enqueues one delivery of {@code payload} to {@code targetInbox}, signed as the named actor. */
	@Transactional
	public DeliveryTask enqueue(String targetInbox, FederationKey.ActorType keyType, String keyRef, String keyId,
		byte[] payload)
	{
		DeliveryTask task = new DeliveryTask();
		task.targetInbox = targetInbox;
		task.actorKeyType = keyType;
		task.actorKeyRef = keyRef;
		task.signerKeyId = keyId;
		task.payload = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
		task.nextAttemptAt = Instant.now();
		task.persist();
		return task;
	}

	/** Scheduled drain pass — picks up due tasks and attempts each. */
	@Scheduled(every = "10s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
	void drain()
	{
		if (config.operational())
		{
			drainOnce();
		}
	}

	/** Attempts every currently-due task once. Returns the number attempted. */
	public int drainOnce()
	{
		List<DeliveryTask> due = findDue();
		for (DeliveryTask task : due)
		{
			attempt(task.id);
		}
		return due.size();
	}

	@Transactional
	List<DeliveryTask> findDue()
	{
		return tasks.findDue(Instant.now());
	}

	/** Attempts a single delivery and records the outcome (state, attempts, backoff). */
	@Transactional
	public void attempt(java.util.UUID taskId)
	{
		DeliveryTask task = tasks.findById(taskId);
		if (task == null || task.state != DeliveryTask.State.PENDING)
		{
			return;
		}
		FederationKey signer = keys.findByActorTypeAndActorRef(task.actorKeyType, task.actorKeyRef).orElse(null);
		ActivityPubClient.DeliveryOutcome outcome = signer == null
			? new ActivityPubClient.DeliveryOutcome(false, 0, "missing signing key")
			: client.deliver(task.targetInbox, task.payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
				signer, task.signerKeyId);

		task.attempts += 1;
		if (outcome.success())
		{
			task.state = DeliveryTask.State.DELIVERED;
			task.lastError = null;
		}
		else if (task.attempts >= config.maxDeliveryAttempts())
		{
			task.state = DeliveryTask.State.FAILED;
			task.lastError = outcome.error();
			LOG.warnf("Delivery to %s dead-lettered after %d attempts: %s", task.targetInbox, task.attempts,
				outcome.error());
		}
		else
		{
			task.nextAttemptAt = Instant.now().plus(backoff(task.attempts));
			task.lastError = outcome.error();
		}
	}

	private static Duration backoff(int attempts)
	{
		// 1m, 2m, 4m, ... capped at 1h
		long minutes = Math.min(60, 1L << Math.min(attempts - 1, 6));
		return Duration.ofMinutes(minutes);
	}

}
