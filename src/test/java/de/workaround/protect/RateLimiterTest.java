package de.workaround.protect;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fixed-window counting: a key may spend its budget inside one window, is refused once the budget
 * is gone, and starts over when the window rolls. Keys never share a budget.
 */
class RateLimiterTest
{
	private static final Duration WINDOW = Duration.ofMinutes(1);

	@Test
	void requestsUpToTheLimitPassAndTheNextOneIsRefused()
	{
		RateLimiter limiter = RateLimiter.withClock(() -> 0L);

		assertTrue(limiter.tryAcquire("ip:1.2.3.4", 2, WINDOW));
		assertTrue(limiter.tryAcquire("ip:1.2.3.4", 2, WINDOW));
		assertFalse(limiter.tryAcquire("ip:1.2.3.4", 2, WINDOW));
	}

	@Test
	void theBudgetIsRestoredWhenTheWindowRolls()
	{
		AtomicLong now = new AtomicLong(0);
		RateLimiter limiter = RateLimiter.withClock(now::get);

		assertTrue(limiter.tryAcquire("ip:1.2.3.4", 1, WINDOW));
		assertFalse(limiter.tryAcquire("ip:1.2.3.4", 1, WINDOW));

		now.set(WINDOW.toMillis());
		assertTrue(limiter.tryAcquire("ip:1.2.3.4", 1, WINDOW));
	}

	@Test
	void keysAreCountedIndependently()
	{
		RateLimiter limiter = RateLimiter.withClock(() -> 0L);

		assertTrue(limiter.tryAcquire("ip:1.2.3.4", 1, WINDOW));
		assertFalse(limiter.tryAcquire("ip:1.2.3.4", 1, WINDOW));
		assertTrue(limiter.tryAcquire("user:alice", 1, WINDOW));
	}

	@Test
	void aZeroLimitRefusesEverything()
	{
		RateLimiter limiter = RateLimiter.withClock(() -> 0L);

		assertFalse(limiter.tryAcquire("ip:1.2.3.4", 0, WINDOW));
	}

	@Test
	void resetClearsEveryTrackedKey()
	{
		RateLimiter limiter = RateLimiter.withClock(() -> 0L);
		limiter.tryAcquire("ip:1.2.3.4", 1, WINDOW);

		limiter.reset();

		assertTrue(limiter.tryAcquire("ip:1.2.3.4", 1, WINDOW));
	}

	@Test
	void expiredKeysAreEvictedSoTheMapDoesNotGrowForever()
	{
		AtomicLong now = new AtomicLong(0);
		RateLimiter limiter = RateLimiter.withClock(now::get);
		for (int i = 0; i < 100; i++)
		{
			limiter.tryAcquire("ip:10.0.0." + i, 5, WINDOW);
		}

		now.set(WINDOW.toMillis() * 2);
		limiter.pruneExpired(WINDOW);

		assertTrue(limiter.tracked() == 0, "expired windows must be dropped, tracked=" + limiter.tracked());
	}
}
