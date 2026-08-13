package de.workaround.api;

import java.util.List;

import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.ReleaseService;
import de.workaround.model.Release;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * JSON REST access to a repository's releases under {@code /api/v1/repos/{owner}/{name}/releases}, shaped
 * like Gitea's release API. Releases are addressed by their tag. Listing/reading follow repository
 * read-visibility; creating, editing and deleting require a token and write access (enforced by
 * {@link ReleaseService}). Draft releases do not exist in git-shark.
 */
@Path("/api/v1/repos/{owner}/{name}/releases")
@Produces(MediaType.APPLICATION_JSON)
public class ReleaseApiResource
{
	@Inject
	GitRepositoryService repositories;

	@Inject
	ReleaseService releases;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	ApiPrincipal principal;

	@Context
	UriInfo uriInfo;

	@GET
	public List<ApiModels.ReleaseView> list(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		String archiveBase = archiveBase(repo);
		return releases.list(repo).stream().map(release -> ApiModels.ReleaseView.of(release, archiveBase)).toList();
	}

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	public Response create(@PathParam("owner") String owner, @PathParam("name") String name,
		ApiModels.NewRelease request)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		Release release = releases.create(user, repo, request.tagName(), request.targetCommitish(), request.name(),
			request.body(), request.prerelease());
		return Response.status(Response.Status.CREATED)
			.entity(ApiModels.ReleaseView.of(release, archiveBase(repo)))
			.build();
	}

	/** The newest non-prerelease release, or 404 when the repository has none. */
	@GET
	@Path("latest")
	public ApiModels.ReleaseView latest(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		Release release = releases.findLatest(repo).orElseThrow(NotFoundException::new);
		return ApiModels.ReleaseView.of(release, archiveBase(repo));
	}

	@GET
	@Path("tags/{tag:.+}")
	public ApiModels.ReleaseView get(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("tag") String tag)
	{
		Repository repo = requireReadable(owner, name);
		return ApiModels.ReleaseView.of(require(repo, tag), archiveBase(repo));
	}

	@PATCH
	@Path("tags/{tag:.+}")
	@Consumes(MediaType.APPLICATION_JSON)
	@Transactional
	public ApiModels.ReleaseView update(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("tag") String tag, ApiModels.ReleaseEdit request)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		Release release = require(repo, tag);
		// null means "leave unchanged", so every field falls back to what the release already carries
		releases.update(user, release, request.name() == null ? release.title : request.name(),
			request.body() == null ? release.body : request.body(),
			request.prerelease() == null ? release.prerelease : request.prerelease());
		return ApiModels.ReleaseView.of(require(repo, tag), archiveBase(repo));
	}

	@DELETE
	@Path("tags/{tag:.+}")
	public Response delete(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("tag") String tag)
	{
		User user = principal.require();
		Repository repo = requireReadable(owner, name);
		releases.delete(user, require(repo, tag));
		return Response.noContent().build();
	}

	private Release require(Repository repo, String tag)
	{
		return releases.find(repo, tag).orElseThrow(NotFoundException::new);
	}

	/** Absolute prefix the archive URLs are built from, e.g. {@code https://host/repos/alice/demo/archive/}. */
	private String archiveBase(Repository repo)
	{
		return uriInfo.getBaseUri()
			.resolve("/repos/" + repo.ownerHandle() + "/" + repo.name + "/archive/")
			.toString();
	}

	private Repository requireReadable(String owner, String name)
	{
		Repository repo = repositories.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(principal.orNull(), repo))
		{
			// a private repository must not even confirm its existence
			throw new NotFoundException();
		}
		return repo;
	}

}
