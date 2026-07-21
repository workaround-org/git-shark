package de.workaround.api;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The Gitea {@code GET /api/v1/version} probe. Gitea clients call it first to gate which features they use,
 * so the reported string must match what git-shark actually implements: it is kept deliberately low (below
 * 1.14.0) so Renovate does not call the reviewer/label/branch-protection endpoints that are not wired yet.
 * Raise {@code gitshark.gitea-api.version} as those capabilities land. Open to anonymous callers.
 */
@Path("/api/v1/version")
@Produces(MediaType.APPLICATION_JSON)
public class VersionApiResource
{
	@ConfigProperty(name = "gitshark.gitea-api.version")
	String version;

	@GET
	public ApiModels.VersionView version()
	{
		return new ApiModels.VersionView(version);
	}
}
