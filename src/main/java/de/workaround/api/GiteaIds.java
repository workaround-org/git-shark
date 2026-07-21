package de.workaround.api;

import java.util.UUID;

/**
 * Surrogate numeric ids for the Gitea-compatible {@code /api/v1} contract. Gitea addresses entities by an
 * int64 {@code id}; git-shark keys everything by {@link UUID}. This folds a UUID into a stable, non-negative
 * {@code long} so the wire always carries the same {@code id} for a given entity. The mapping is one-way and
 * lossy — it is a display surrogate only; git-shark never looks an entity up by it (owner/name/number do that).
 */
final class GiteaIds
{
	private GiteaIds()
	{
	}

	static long of(UUID id)
	{
		return (id.getMostSignificantBits() ^ id.getLeastSignificantBits()) & Long.MAX_VALUE;
	}
}
