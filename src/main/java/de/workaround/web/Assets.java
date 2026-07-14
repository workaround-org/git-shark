package de.workaround.web;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;

/**
 * Cache-busting token for static assets (CSS/JS). Fixed once at application startup, so the value
 * changes on every deploy — and on every dev live-reload restart — but stays stable while the app
 * runs. Appended as {@code ?v=...} to asset links in the layout so browsers fetch a changed file
 * instead of serving a stale cached copy, without disabling caching between deploys.
 */
@Named("assets")
@ApplicationScoped
public class Assets
{
	private final String version = Long.toString(System.currentTimeMillis(), 36);

	public String version()
	{
		return version;
	}
}
