package de.workaround.notify;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class NotificationServiceTest
{
	@Inject
	NotificationService service;

	@Test
	void returnsEmptyWhenNoSourcesAreRegistered()
	{
		// No concrete NotificationSource beans ship with this change.
		assertTrue(service.notificationsFor(new User()).isEmpty());
	}

	@Test
	void aggregatesAndOrdersDeterministicallyByCategoryThenTitle()
	{
		NotificationSource issues = user -> List.of(
			new NotificationItem("issue", "Zebra", null, "/z"),
			new NotificationItem("issue", "Apple", null, "/a"));
		NotificationSource mrs = user -> List.of(
			new NotificationItem("merge-request", "Middle", null, "/m"));

		List<NotificationItem> items = service.aggregate(new User(), List.of(mrs, issues));

		assertEquals(List.of("Apple", "Zebra", "Middle"), items.stream().map(NotificationItem::title).toList());
	}

	@Test
	void oneFailingSourceDoesNotBreakAggregation()
	{
		NotificationSource ok = user -> List.of(new NotificationItem("issue", "Survivor", null, "/s"));
		NotificationSource broken = user -> {
			throw new IllegalStateException("boom");
		};

		List<NotificationItem> items = service.aggregate(new User(), List.of(broken, ok));

		assertEquals(1, items.size());
		assertEquals("Survivor", items.get(0).title());
	}

}
