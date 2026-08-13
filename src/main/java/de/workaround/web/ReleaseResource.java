package de.workaround.web;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitBrowseService;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.ReleaseService;
import de.workaround.model.Release;
import de.workaround.model.Repository;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * The repository's Releases pages: the list with its "Latest" badge, the per-tag detail page with rendered
 * notes and source-archive links, and the publish/edit/delete forms. Reading follows repository visibility;
 * every mutation is authorized in {@link ReleaseService}.
 */
@jakarta.ws.rs.Path("/repos/{owner}/{name}/releases")
@Produces(MediaType.TEXT_HTML)
public class ReleaseResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance releases(Repository repo, RepoNav nav, boolean canWrite,
			List<Release> releases, String latestTag);

		static native TemplateInstance newRelease(Repository repo, RepoNav nav, List<String> targets,
			List<String> untaggedTags);

		static native TemplateInstance release(Repository repo, RepoNav nav, boolean canWrite, Release release,
			String bodyHtml, boolean latest);

		static native TemplateInstance editRelease(Repository repo, RepoNav nav, Release release);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService service;

	@Inject
	GitBrowseService browse;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	ReleaseService releaseService;

	@Inject
	RepoNavService repoNav;

	@Context
	UriInfo uriInfo;

	@GET
	public TemplateInstance list(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		String latestTag = releaseService.findLatest(repo).map(release -> release.tagName).orElse(null);
		return Templates.releases(repo, repoNav.build(repo, uriInfo), canWrite(repo), releaseService.list(repo),
			latestTag);
	}

	@GET
	@jakarta.ws.rs.Path("new")
	public TemplateInstance newForm(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireWritable(owner, name);
		Path barePath = service.repositoryPath(repo);
		List<String> branches = browse.branches(barePath).stream().map(GitBrowseService.BranchInfo::name).toList();
		// tags that have no release yet can be published as-is; the rest need a new tag cut from a branch
		List<String> untagged = browse.tags(barePath).stream()
			.filter(tag -> releaseService.find(repo, tag).isEmpty())
			.toList();
		return Templates.newRelease(repo, repoNav.build(repo, uriInfo), branches, untagged);
	}

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response create(@PathParam("owner") String owner, @PathParam("name") String name,
		@FormParam("tagName") String tagName, @FormParam("target") String target,
		@FormParam("title") String title, @FormParam("body") String body,
		@FormParam("prerelease") @DefaultValue("") String prerelease)
	{
		Repository repo = requireReadable(owner, name);
		Release release = releaseService.create(currentUser.require(), repo, tagName, target, title, body,
			checked(prerelease));
		return Response.seeOther(releaseUri(repo, release.tagName)).build();
	}

	@GET
	@jakarta.ws.rs.Path("tag/{tag:.+}")
	public TemplateInstance detail(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("tag") String tag)
	{
		Repository repo = requireReadable(owner, name);
		Release release = require(repo, tag);
		String bodyHtml = release.body == null ? null : Markdown.render(release.body);
		boolean latest = releaseService.findLatest(repo).filter(r -> r.id.equals(release.id)).isPresent();
		return Templates.release(repo, repoNav.build(repo, uriInfo), canWrite(repo), release, bodyHtml, latest);
	}

	@GET
	@jakarta.ws.rs.Path("tag/{tag:.+}/edit")
	public TemplateInstance editForm(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("tag") String tag)
	{
		Repository repo = requireWritable(owner, name);
		return Templates.editRelease(repo, repoNav.build(repo, uriInfo), require(repo, tag));
	}

	@POST
	@jakarta.ws.rs.Path("tag/{tag:.+}/edit")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response edit(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("tag") String tag, @FormParam("title") String title, @FormParam("body") String body,
		@FormParam("prerelease") @DefaultValue("") String prerelease)
	{
		Repository repo = requireReadable(owner, name);
		Release release = require(repo, tag);
		releaseService.update(currentUser.require(), release, title, body, checked(prerelease));
		return Response.seeOther(releaseUri(repo, release.tagName)).build();
	}

	@POST
	@jakarta.ws.rs.Path("tag/{tag:.+}/delete")
	public Response delete(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("tag") String tag)
	{
		Repository repo = requireReadable(owner, name);
		releaseService.delete(currentUser.require(), require(repo, tag));
		return Response.seeOther(URI.create("/repos/" + repo.ownerHandle() + "/" + repo.name + "/releases")).build();
	}

	private Release require(Repository repo, String tag)
	{
		return releaseService.find(repo, tag).orElseThrow(NotFoundException::new);
	}

	/** An unchecked checkbox is not submitted at all, so any value present means the box was ticked. */
	private static boolean checked(String value)
	{
		return value != null && !value.isBlank();
	}

	private static URI releaseUri(Repository repo, String tag)
	{
		return URI.create("/repos/" + repo.ownerHandle() + "/" + repo.name + "/releases/tag/"
			+ URLEncoder.encode(tag, StandardCharsets.UTF_8).replace("%2F", "/").replace("+", "%20"));
	}

	private boolean canWrite(Repository repo)
	{
		return accessPolicy.canWrite(currentUser.get(), repo);
	}

	private Repository requireReadable(String owner, String name)
	{
		Repository repo = service.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(currentUser.get(), repo))
		{
			// a private repository must not even confirm its existence
			throw new NotFoundException();
		}
		return repo;
	}

	private Repository requireWritable(String owner, String name)
	{
		Repository repo = requireReadable(owner, name);
		if (!canWrite(repo))
		{
			throw new ForbiddenOperationException(
				"Only the repository owner or a collaborator can manage releases");
		}
		return repo;
	}

}
