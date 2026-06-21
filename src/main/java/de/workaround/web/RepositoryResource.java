package de.workaround.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.GitBrowseService;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@jakarta.ws.rs.Path("/repos/{owner}/{name}")
@Produces(MediaType.TEXT_HTML)
public class RepositoryResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance overview(Repository repo, boolean owner, boolean empty, String defaultBranch,
			List<GitBrowseService.TreeEntry> entries, String httpUrl, String sshUrl);

		static native TemplateInstance tree(Repository repo, String ref, String path,
			List<GitBrowseService.TreeEntry> entries);

		static native TemplateInstance blob(Repository repo, String ref, String path, boolean binary, String content);

		static native TemplateInstance commits(Repository repo, String ref, List<GitBrowseService.CommitInfo> commits,
			int page, int prevPage, int nextPage, int size, boolean hasNext);

		static native TemplateInstance branches(Repository repo, List<GitBrowseService.BranchInfo> branches,
			List<String> tags);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService service;

	@Inject
	GitBrowseService browse;

	@Inject
	AccessPolicy accessPolicy;

	@ConfigProperty(name = "gitshark.ssh.port")
	int sshPort;

	@Context
	UriInfo uriInfo;

	@GET
	public TemplateInstance overview(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		Path path = service.repositoryPath(repo);
		boolean empty = browse.isEmpty(path);
		String defaultBranch = empty ? null : browse.defaultBranch(path);
		List<GitBrowseService.TreeEntry> entries = empty
			? List.of()
			: browse.listTree(path, defaultBranch, "").orElse(List.of());
		User user = currentUser.get();
		boolean isOwner = user != null && user.id.equals(repo.owner.id);
		return Templates.overview(repo, isOwner, empty, defaultBranch, entries, httpUrl(repo), sshUrl(repo));
	}

	@GET
	@Produces({ "application/activity+json", "application/ld+json" })
	public Response overviewActivity(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		// Content negotiation: federation clients asking for activity+json get the actor document.
		// Existence/visibility is enforced at /ap; redirect keeps the JSON-LD surface in one place.
		requireReadable(owner, name);
		return Response.seeOther(URI.create("/ap/repos/" + owner + "/" + name)).build();
	}

	@GET
	@jakarta.ws.rs.Path("tree/{ref}{path:(/.*)?}")
	public TemplateInstance tree(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("ref") String ref, @PathParam("path") String rawPath)
	{
		String path = rawPath == null || rawPath.isEmpty() ? "" : rawPath.substring(1);
		Repository repo = requireReadable(owner, name);
		Path repoPath = service.repositoryPath(repo);
		return browse.listTree(repoPath, ref, path)
			.map(entries -> Templates.tree(repo, ref, path, entries))
			.orElseGet(() -> blobView(repo, repoPath, ref, path));
	}

	@GET
	@jakarta.ws.rs.Path("raw/{ref}/{path:.*}")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response raw(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("ref") String ref, @PathParam("path") String path)
	{
		Repository repo = requireReadable(owner, name);
		GitBrowseService.BlobView blob = browse.blob(service.repositoryPath(repo), ref, path)
			.orElseThrow(NotFoundException::new);
		return Response.ok(blob.content()).build();
	}

	@GET
	@jakarta.ws.rs.Path("commits/{ref}")
	public TemplateInstance commits(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("ref") String ref, @QueryParam("page") @DefaultValue("0") int page,
		@QueryParam("size") @DefaultValue("50") int size)
	{
		Repository repo = requireReadable(owner, name);
		int boundedSize = Math.min(Math.max(size, 1), 100);
		int boundedPage = Math.max(page, 0);
		GitBrowseService.CommitPage commitPage = browse
			.commits(service.repositoryPath(repo), ref, boundedPage, boundedSize)
			.orElseThrow(NotFoundException::new);
		return Templates.commits(repo, ref, commitPage.commits(), boundedPage, boundedPage - 1, boundedPage + 1,
			boundedSize, commitPage.hasNext());
	}

	@GET
	@jakarta.ws.rs.Path("branches")
	public TemplateInstance branches(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		Path path = service.repositoryPath(repo);
		return Templates.branches(repo, browse.branches(path), browse.tags(path));
	}

	@POST
	@jakarta.ws.rs.Path("delete")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response delete(@PathParam("owner") String owner, @PathParam("name") String name,
		@FormParam("confirm") String confirm)
	{
		Repository repo = requireReadable(owner, name);
		if (!repo.name.equals(confirm))
		{
			return Response.status(Response.Status.BAD_REQUEST)
				.entity("Confirmation does not match the repository name").build();
		}
		service.delete(currentUser.require(), repo);
		return Response.seeOther(URI.create("/")).build();
	}

	private TemplateInstance blobView(Repository repo, Path repoPath, String ref, String path)
	{
		GitBrowseService.BlobView blob = browse.blob(repoPath, ref, path).orElseThrow(NotFoundException::new);
		String content = blob.binary() ? null : new String(blob.content(), StandardCharsets.UTF_8);
		return Templates.blob(repo, ref, path, blob.binary(), content);
	}

	private Repository requireReadable(String owner, String name)
	{
		Repository repo = service.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(currentUser.get(), repo))
		{
			// hide existence of private repositories
			throw new NotFoundException();
		}
		return repo;
	}

	private String httpUrl(Repository repo)
	{
		return uriInfo.getBaseUri().resolve("/git/" + repo.owner.username + "/" + repo.name + ".git").toString();
	}

	private String sshUrl(Repository repo)
	{
		return "ssh://git@" + uriInfo.getBaseUri().getHost() + ":" + sshPort
			+ "/" + repo.owner.username + "/" + repo.name + ".git";
	}

}
