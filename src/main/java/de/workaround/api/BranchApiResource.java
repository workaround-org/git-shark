package de.workaround.api;

import de.workaround.git.AccessPolicy;
import de.workaround.git.GitBrowseService;
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
 * The Gitea {@code GET /api/v1/repos/{owner}/{name}/branches/{branch}} endpoint. Renovate reads the returned
 * {@code commit.id} as the branch tip SHA. The branch segment is matched greedily so slash-bearing names
 * (e.g. {@code renovate/foo}) resolve; only real branch refs count, so a tag or raw SHA yields 404. Reads
 * follow repository visibility (private repos hidden as 404).
 */
@Path("/api/v1/repos/{owner}/{name}/branches")
@Produces(MediaType.APPLICATION_JSON)
public class BranchApiResource
{
	@Inject
	GitRepositoryService repositories;

	@Inject
	GitBrowseService browse;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	ApiPrincipal principal;

	@GET
	@Path("{branch:.+}")
	public ApiModels.BranchView get(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("branch") String branch)
	{
		Repository repo = requireReadable(owner, name);
		GitBrowseService.CommitInfo tip = browse.commit(repositories.repositoryPath(repo), "refs/heads/" + branch)
			.orElseThrow(NotFoundException::new);
		return new ApiModels.BranchView(branch, new ApiModels.BranchView.CommitRef(tip.id()), false);
	}

	private Repository requireReadable(String owner, String name)
	{
		Repository repo = repositories.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(principal.orNull(), repo))
		{
			throw new NotFoundException();
		}
		return repo;
	}
}
