package de.workaround.api;

import java.util.List;

import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * The Gitea {@code GET /api/v1/repos/{owner}/{name}/labels} endpoint. git-shark has no label model yet, so
 * this always returns an empty list — enough for Renovate, which skips label handling when the list is empty.
 * Reads follow repository visibility (private repos hidden as 404).
 */
@Path("/api/v1/repos/{owner}/{name}/labels")
@Produces(MediaType.APPLICATION_JSON)
public class LabelApiResource
{
	@Inject
	GitRepositoryService repositories;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	ApiPrincipal principal;

	@GET
	public List<Object> list(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		requireReadable(owner, name);
		return List.of();
	}

	private void requireReadable(String owner, String name)
	{
		Repository repo = repositories.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(principal.orNull(), repo))
		{
			throw new NotFoundException();
		}
	}
}
