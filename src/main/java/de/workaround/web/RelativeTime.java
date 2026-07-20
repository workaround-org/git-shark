package de.workaround.web;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

import io.quarkus.qute.TemplateExtension;

/**
 * Human-friendly relative timestamps for the UI. Raw ISO instants (e.g. 2026-07-20T11:26:09.565647Z) are
 * hard to read at a glance, so render "just now", "10min ago" or "6h ago" for recent times and fall back to
 * a plain UTC date once something is more than a day old. Exposed to Qute as {instant.since}.
 */
public final class RelativeTime
{
	private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC);

	private RelativeTime()
	{
	}

	@TemplateExtension
	public static String since(Instant instant)
	{
		return format(instant, Instant.now());
	}

	/** Package-visible with an explicit "now" so the formatting is deterministically testable. */
	static String format(Instant instant, Instant now)
	{
		if (instant == null)
		{
			return "";
		}
		long seconds = Duration.between(instant, now).getSeconds();
		if (seconds < 60)
		{
			// covers future timestamps (negative durations) too
			return "just now";
		}
		long minutes = seconds / 60;
		if (minutes < 60)
		{
			return minutes + "min ago";
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + "h ago";
		}
		return DATE.format(instant);
	}
}
