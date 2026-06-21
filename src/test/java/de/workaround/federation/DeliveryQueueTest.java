package de.workaround.federation;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.DeliveryTask;
import de.workaround.model.FederationKey;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Section 3: the delivery queue persists tasks (survive a restart), retries an unreachable peer
 * with backoff, and dead-letters after the maximum attempts without throwing.
 */
@QuarkusTest
class DeliveryQueueTest
{
	@Inject
	DeliveryService delivery;

	@Inject
	ActorKeyService keyService;

	@Inject
	DeliveryTask.Repo tasks;

	@Inject
	FederationConfig config;

	@Test
	void enqueuedTaskIsPersistedAndDue()
	{
		keyService.getOrCreate(FederationKey.ActorType.INSTANCE, "instance");
		UUID id = enqueue();

		DeliveryTask persisted = byId(id);
		assertNotNull(persisted);
		assertEquals(DeliveryTask.State.PENDING, persisted.state);
		// "survives a restart": still discoverable purely from the database.
		assertTrue(tasks.findDue(Instant.now()).stream().anyMatch(t -> t.id.equals(id)));
	}

	@Test
	void unreachablePeerIsRetriedThenDeadLettered()
	{
		keyService.getOrCreate(FederationKey.ActorType.INSTANCE, "instance");
		UUID id = enqueue();

		// peer.test is allowlisted but does not resolve → every attempt fails.
		for (int i = 0; i < config.maxDeliveryAttempts(); i++)
		{
			delivery.attempt(id);
		}

		DeliveryTask done = byId(id);
		assertEquals(DeliveryTask.State.FAILED, done.state);
		assertEquals(config.maxDeliveryAttempts(), done.attempts);
		assertNotNull(done.lastError);
	}

	@Transactional
	UUID enqueue()
	{
		return delivery.enqueue("https://peer.test/ap/users/frank/inbox", FederationKey.ActorType.INSTANCE,
			"instance", "https://shark.test/ap/instance#main-key",
			("{\"type\":\"Create\",\"id\":\"https://shark.test/a/" + UUID.randomUUID() + "\"}")
				.getBytes(StandardCharsets.UTF_8)).id;
	}

	@Transactional
	DeliveryTask byId(UUID id)
	{
		return tasks.findById(id);
	}

}
