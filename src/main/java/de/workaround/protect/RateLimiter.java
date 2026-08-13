package de.workaround.protect;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * In-memory fixed-window request counter, keyed by caller (user account or client IP). Fixed windows
 * over sliding ones on purpose: one map entry per caller, no per-request timestamp lists, and the
 * worst case — twice the budget across a window boundary — is irrelevant for an abuse guard.
 *
 * <p>State is per instance and deliberately not replicated: with several pods each enforces its own
 * share of the budget, which is enough to stop a crawler hammering one node. The map is pruned of
 * elapsed windows once it grows past {@link #MAX_KEYS} so a spray of unique IPs cannot grow it
 * without bound.
 */
@ApplicationScoped
public class RateLimiter
{
	private static final int MAX_KEYS = 50_000;

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	private final LongSupplier clock;

	RateLimiter()
	{
		this(System::currentTimeMillis);
	}

	private RateLimiter(LongSupplier clock)
	{
		this.clock = clock;
	}

	/** Test entry point: a limiter driven by a controllable millisecond clock. */
	public static RateLimiter withClock(LongSupplier clock)
	{
		return new RateLimiter(clock);
	}

	/**
	 * Counts one request against {@code key} and reports whether it is still within {@code limit}
	 * for the current window.
	 */
	public boolean tryAcquire(String key, int limit, Duration window)
	{
		if (limit <= 0)
		{
			return false;
		}
		long now = clock.getAsLong();
		long windowMillis = Math.max(1, window.toMillis());
		if (windows.size() >= MAX_KEYS)
		{
			pruneExpired(window);
		}
		Window counted = windows.compute(key, (ignored, current) ->
		{
			if (current == null || now - current.start() >= windowMillis)
			{
				return new Window(now, 1);
			}
			return new Window(current.start(), current.count() + 1);
		});
		return counted.count() <= limit;
	}

	/** Drops entries whose window has elapsed; safe to call at any time. */
	public void pruneExpired(Duration window)
	{
		long now = clock.getAsLong();
		long windowMillis = Math.max(1, window.toMillis());
		windows.values().removeIf(entry -> now - entry.start() >= windowMillis);
	}

	/** Forgets every budget — used by tests to isolate one scenario from the next. */
	public void reset()
	{
		windows.clear();
	}

	/** Number of callers currently tracked. */
	public int tracked()
	{
		return windows.size();
	}

	private record Window(long start, int count)
	{
	}
}
