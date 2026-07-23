package de.workaround.api;

import java.util.List;

import de.workaround.ci.CommitStatusService;
import de.workaround.git.AccessPolicy;
import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitBrowseService;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionRun;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Gitea commit-status endpoints. The combined/list reads report the repository's CI runs for the resolved
 * commit (one status per run); a commit with no runs stays all-clear {@code success} so Renovate proceeds.
 * {@code POST /statuses/{sha}} still only echoes the posted status (git-shark statuses come from its own runs,
 * not external posts). The {@code ref} segment is matched greedily so slash-bearing branch names resolve.
 * Reads follow repository visibility; posting a status needs a token and write access.
 */
@Path("/api/v1/repos/{owner}/{name}")
@Produces(MediaType.APPLICATION_JSON)
public class CommitStatusApiResource
{
	@Inject
	GitRepositoryService repositories;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	ApiPrincipal principal;

	@Inject
	GitBrowseService browse;

	@Inject
	CommitStatusService commitStatus;

	@GET
	@Path("commits/{ref:.+}/status")
	public ApiModels.CombinedStatusView combined(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("ref") String ref)
	{
		Repository repo = readable(owner, name);
		String sha = resolveSha(repo, ref);
		List<ActionRun> found = commitStatus.runsFor(repo, sha);
		if (found.isEmpty())
		{
			// no runs for this commit — stay all-clear so Renovate and other clients proceed
			return ApiModels.CombinedStatusView.allClear(sha);
		}
		String state = CommitStatusService.toGiteaState(
			commitStatus.aggregate(found).orElse(ActionRun.Status.SUCCESS));
		List<ApiModels.CommitStatusView> statuses = toStatusViews(found);
		return new ApiModels.CombinedStatusView(state, state, sha, statuses.size(), statuses);
	}

	@GET
	@Path("commits/{ref:.+}/statuses")
	public List<ApiModels.CommitStatusView> list(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("ref") String ref)
	{
		Repository repo = readable(owner, name);
		return toStatusViews(commitStatus.runsFor(repo, resolveSha(repo, ref)));
	}

	private static List<ApiModels.CommitStatusView> toStatusViews(List<ActionRun> runs)
	{
		return runs.stream()
			.map(run -> new ApiModels.CommitStatusView(run.number, CommitStatusService.toGiteaState(run.status),
				"ci/" + run.workflowName, run.status.label, ""))
			.toList();
	}

	private String resolveSha(Repository repo, String ref)
	{
		return browse.commit(repositories.repositoryPath(repo), ref)
			.map(GitBrowseService.CommitInfo::id)
			.orElse(ref);
	}

	@POST
	@Path("statuses/{sha}")
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("sha") String sha, ApiModels.NewStatus request)
	{
		User user = principal.require();
		Repository repo = repositories.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(principal.orNull(), repo))
		{
			throw new NotFoundException();
		}
		if (!accessPolicy.canWrite(user, repo))
		{
			throw new ForbiddenOperationException("Only the repository owner or a collaborator can post statuses");
		}
		ApiModels.CommitStatusView echoed = new ApiModels.CommitStatusView(0, request.state(), request.context(),
			request.description(), request.targetUrl());
		return Response.status(Response.Status.CREATED).entity(echoed).build();
	}

	private Repository readable(String owner, String name)
	{
		Repository repo = repositories.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(principal.orNull(), repo))
		{
			throw new NotFoundException();
		}
		return repo;
	}
}
