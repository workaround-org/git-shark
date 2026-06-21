package de.workaround.federation;

/**
 * Media types and the pinned JSON-LD {@code @context} used across federation. We emit and consume
 * compacted documents against this fixed context rather than running a full JSON-LD processor.
 */
public final class ActivityPubMedia
{
	public static final String ACTIVITY_JSON = "application/activity+json";

	public static final String LD_JSON = "application/ld+json";

	public static final String JRD_JSON = "application/jrd+json";

	public static final String ACTIVITYSTREAMS = "https://www.w3.org/ns/activitystreams";

	public static final String SECURITY = "https://w3id.org/security/v1";

	public static final String FORGEFED = "https://forgefed.org/ns";

	private ActivityPubMedia()
	{
	}

}
