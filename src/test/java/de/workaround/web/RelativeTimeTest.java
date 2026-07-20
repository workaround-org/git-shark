package de.workaround.web;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RelativeTimeTest
{
	private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

	@Test
	void withinAMinuteReadsJustNow()
	{
		assertEquals("just now", RelativeTime.format(NOW.minusSeconds(30), NOW));
	}

	@Test
	void withinAnHourReadsMinutesAgo()
	{
		assertEquals("10min ago", RelativeTime.format(NOW.minusSeconds(10 * 60), NOW));
	}

	@Test
	void withinADayReadsHoursAgo()
	{
		assertEquals("6h ago", RelativeTime.format(NOW.minusSeconds(6 * 3600), NOW));
	}

	@Test
	void olderThanADayReadsAsADate()
	{
		assertEquals("2026-07-17", RelativeTime.format(NOW.minusSeconds(3 * 24 * 3600), NOW));
	}

	@Test
	void futureTimestampsClampToJustNow()
	{
		assertEquals("just now", RelativeTime.format(NOW.plusSeconds(5), NOW));
	}

	@Test
	void nullIsEmpty()
	{
		assertEquals("", RelativeTime.format(null, NOW));
	}
}
