package de.workaround.web;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import de.workaround.account.CurrentUser;
import de.workaround.account.InvalidImageException;
import de.workaround.git.AccessPolicy;
import de.workaround.git.GitBrowseService;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.RepositoryImageService;
import de.workaround.git.RepositoryPinService;
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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@jakarta.ws.rs.Path("/repos/{owner}/{name}")
@Produces(MediaType.TEXT_HTML)
public class RepositoryResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance overview(Repository repo, RepoNav nav, boolean owner,
			List<GitBrowseService.TreeEntry> entries, GitBrowseService.CommitInfo latestCommit, String latestCommitAge,
			String readmeName, String readmeHtml, List<de.workaround.model.PushMirror> mirrors);

		static native TemplateInstance tree(Repository repo, RepoNav nav, String ref, String path,
			List<GitBrowseService.TreeEntry> entries, List<Crumb> crumbs);

		static native TemplateInstance blob(Repository repo, RepoNav nav, String ref, String path, boolean binary,
			String content, String language, String markdownHtml, List<Crumb> crumbs);

		static native TemplateInstance commits(Repository repo, RepoNav nav, String ref,
			List<GitBrowseService.CommitInfo> commits, int page, int prevPage, int nextPage, int size,
			boolean hasNext);

		static native TemplateInstance branches(Repository repo, RepoNav nav,
			List<GitBrowseService.BranchInfo> branches);

		static native TemplateInstance tags(Repository repo, RepoNav nav, List<String> tags);

		static native TemplateInstance settings(Repository repo, RepoNav nav, String error);
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
	RepositoryPinService pinService;

	@Inject
	RepoNavService repoNav;

	@Inject
	de.workaround.mirror.MirrorService mirrorService;

	@Inject
	RepositoryImageService images;

	@Context
	UriInfo uriInfo;

	@GET
	public TemplateInstance overview(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		RepoNav nav = repoNav.build(repo, uriInfo);
		Path path = service.repositoryPath(repo);
		List<GitBrowseService.TreeEntry> entries = nav.empty()
			? List.of()
			: browse.listTree(path, nav.defaultBranch(), "").orElse(List.of());
		User user = currentUser.get();
		boolean isOwner = user != null && user.id.equals(repo.owner.id);
		GitBrowseService.CommitInfo latestCommit = nav.empty() ? null
			: browse.commits(path, nav.defaultBranch(), 0, 1)
				.filter(commitPage -> !commitPage.commits().isEmpty())
				.map(commitPage -> commitPage.commits().get(0))
				.orElse(null);
		String latestCommitAge = latestCommit == null ? null : relativeAge(latestCommit.date());
		GitBrowseService.TreeEntry readmeEntry = findReadme(entries);
		String readmeName = readmeEntry == null ? null : readmeEntry.name();
		String readmeHtml = readmeEntry == null ? null : browse.blob(path, nav.defaultBranch(), readmeEntry.path())
			.filter(blob -> !blob.binary())
			.map(blob -> Markdown.render(new String(blob.content(), StandardCharsets.UTF_8)))
			.orElse(null);
		List<de.workaround.model.PushMirror> mirrors = isOwner ? mirrorService.list(repo) : List.of();
		return Templates.overview(repo, nav, isOwner, entries, latestCommit, latestCommitAge, readmeName, readmeHtml,
			mirrors);
	}

	// README file names the overview looks for, in order of preference (matched case-insensitively).
	private static final List<String> README_NAMES = List.of("readme.md", "readme.markdown", "readme", "readme.txt");

	private static GitBrowseService.TreeEntry findReadme(List<GitBrowseService.TreeEntry> entries)
	{
		for (String candidate : README_NAMES)
		{
			for (GitBrowseService.TreeEntry entry : entries)
			{
				if (!entry.directory() && entry.name().toLowerCase(Locale.ROOT).equals(candidate))
				{
					return entry;
				}
			}
		}
		return null;
	}

	private static String relativeAge(Instant when)
	{
		Duration elapsed = Duration.between(when, Instant.now());
		if (elapsed.isNegative())
		{
			elapsed = Duration.ZERO;
		}
		long days = elapsed.toDays();
		if (days >= 365)
		{
			return plural(days / 365, "year");
		}
		if (days >= 30)
		{
			return plural(days / 30, "month");
		}
		if (days >= 1)
		{
			return plural(days, "day");
		}
		long hours = elapsed.toHours();
		if (hours >= 1)
		{
			return plural(hours, "hour");
		}
		long minutes = elapsed.toMinutes();
		if (minutes >= 1)
		{
			return plural(minutes, "minute");
		}
		return "just now";
	}

	private static String plural(long count, String unit)
	{
		return count + " " + unit + (count == 1 ? "" : "s") + " ago";
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
		RepoNav nav = repoNav.build(repo, uriInfo);
		Path repoPath = service.repositoryPath(repo);
		return browse.listTree(repoPath, ref, path)
			.map(entries -> Templates.tree(repo, nav, ref, path, entries, breadcrumbs(repo, ref, path)))
			.orElseGet(() -> blobView(repo, nav, repoPath, ref, path));
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
		RepoNav nav = repoNav.build(repo, uriInfo);
		int boundedSize = Math.min(Math.max(size, 1), 100);
		int boundedPage = Math.max(page, 0);
		GitBrowseService.CommitPage commitPage = browse
			.commits(service.repositoryPath(repo), ref, boundedPage, boundedSize)
			.orElseThrow(NotFoundException::new);
		return Templates.commits(repo, nav, ref, commitPage.commits(), boundedPage, boundedPage - 1, boundedPage + 1,
			boundedSize, commitPage.hasNext());
	}

	@GET
	@jakarta.ws.rs.Path("branches")
	public TemplateInstance branches(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		RepoNav nav = repoNav.build(repo, uriInfo);
		Path path = service.repositoryPath(repo);
		return Templates.branches(repo, nav, browse.branches(path));
	}

	@GET
	@jakarta.ws.rs.Path("tags")
	public TemplateInstance tags(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		RepoNav nav = repoNav.build(repo, uriInfo);
		Path path = service.repositoryPath(repo);
		return Templates.tags(repo, nav, browse.tags(path));
	}

	@GET
	@jakarta.ws.rs.Path("settings")
	public TemplateInstance settings(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireOwner(owner, name);
		return Templates.settings(repo, repoNav.build(repo, uriInfo), null);
	}

	@POST
	@jakarta.ws.rs.Path("image")
	@Consumes(MediaType.MULTIPART_FORM_DATA)
	public Response uploadImage(@PathParam("owner") String owner, @PathParam("name") String name,
		@RestForm("image") FileUpload image)
	{
		Repository repo = requireOwner(owner, name);
		if (image == null)
		{
			return Response.status(Response.Status.BAD_REQUEST)
				.entity(Templates.settings(repo, repoNav.build(repo, uriInfo), "No image was uploaded.")).build();
		}
		try
		{
			images.store(currentUser.require(), repo, Files.readAllBytes(image.uploadedFile()), image.contentType());
			return Response.seeOther(settingsUri(repo)).build();
		}
		catch (InvalidImageException e)
		{
			return Response.status(Response.Status.BAD_REQUEST)
				.entity(Templates.settings(repo, repoNav.build(repo, uriInfo), e.getMessage())).build();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Failed to read uploaded repository image", e);
		}
	}

	@POST
	@jakarta.ws.rs.Path("image/delete")
	public Response deleteImage(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireOwner(owner, name);
		images.remove(currentUser.require(), repo);
		return Response.seeOther(settingsUri(repo)).build();
	}

	@GET
	@jakarta.ws.rs.Path("image")
	@Produces(MediaType.APPLICATION_OCTET_STREAM)
	public Response image(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		// Visibility-guarded (unlike the public user avatar): a private repo's image must not leak.
		Repository repo = requireReadable(owner, name);
		if (!repo.hasImage())
		{
			throw new NotFoundException();
		}
		byte[] bytes = images.read(repo).orElseThrow(NotFoundException::new);
		// Immutable is safe: rendered image URLs carry ?v=<imageUpdatedAt>. Private repos must not
		// land in shared caches (the URL leaks to proxies even though the response is guarded).
		String cacheScope = repo.visibility == Repository.Visibility.PUBLIC ? "public" : "private";
		return Response.ok(bytes).type(repo.imageContentType)
			.header(HttpHeaders.CACHE_CONTROL, cacheScope + ", max-age=31536000, immutable")
			.build();
	}

	private static URI settingsUri(Repository repo)
	{
		return URI.create("/repos/" + repo.owner.username + "/" + repo.name + "/settings");
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

	@POST
	@jakarta.ws.rs.Path("pin")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response pin(@PathParam("owner") String owner, @PathParam("name") String name,
		@FormParam("redirect") @DefaultValue("/") String redirect)
	{
		Repository repo = requireReadable(owner, name);
		pinService.pin(currentUser.require(), repo);
		return Response.seeOther(safeRedirect(redirect)).build();
	}

	@POST
	@jakarta.ws.rs.Path("unpin")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response unpin(@PathParam("owner") String owner, @PathParam("name") String name,
		@FormParam("redirect") @DefaultValue("/") String redirect)
	{
		Repository repo = requireReadable(owner, name);
		pinService.unpin(currentUser.require(), repo);
		return Response.seeOther(safeRedirect(redirect)).build();
	}

	private static URI safeRedirect(String redirect)
	{
		// only allow same-site relative paths to avoid open-redirect
		if (redirect != null && redirect.startsWith("/") && !redirect.startsWith("//"))
		{
			return URI.create(redirect);
		}
		return URI.create("/");
	}

	private TemplateInstance blobView(Repository repo, RepoNav nav, Path repoPath, String ref, String path)
	{
		GitBrowseService.BlobView blob = browse.blob(repoPath, ref, path).orElseThrow(NotFoundException::new);
		String content = blob.binary() ? null : new String(blob.content(), StandardCharsets.UTF_8);
		String language = blob.binary() ? null : highlightLanguage(path);
		String markdownHtml = "markdown".equals(language) ? Markdown.render(content) : null;
		return Templates.blob(repo, nav, ref, path, blob.binary(), content, language, markdownHtml,
			breadcrumbs(repo, ref, path));
	}

	// Extension → highlight.js language id. Every value here MUST have a grammar in the bundled highlight assets
	// (highlight.min.js + highlight-extra.min.js); WebUiTest guards that so a new mapping can't silently no-op.
	private static final Map<String, String> HIGHLIGHT_BY_EXTENSION = Map.ofEntries(
		Map.entry("java", "java"),
		Map.entry("js", "javascript"), Map.entry("mjs", "javascript"), Map.entry("cjs", "javascript"),
		Map.entry("jsx", "javascript"),
		Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
		Map.entry("py", "python"),
		Map.entry("rb", "ruby"),
		Map.entry("go", "go"),
		Map.entry("rs", "rust"),
		Map.entry("c", "c"), Map.entry("h", "c"),
		Map.entry("cc", "cpp"), Map.entry("cpp", "cpp"), Map.entry("cxx", "cpp"), Map.entry("hpp", "cpp"),
		Map.entry("hxx", "cpp"),
		Map.entry("cs", "csharp"),
		Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"),
		Map.entry("swift", "swift"),
		Map.entry("scala", "scala"),
		Map.entry("groovy", "groovy"), Map.entry("gradle", "groovy"),
		Map.entry("php", "php"),
		Map.entry("pl", "perl"), Map.entry("pm", "perl"),
		Map.entry("lua", "lua"),
		Map.entry("r", "r"),
		Map.entry("dart", "dart"),
		Map.entry("sh", "bash"), Map.entry("bash", "bash"), Map.entry("zsh", "bash"),
		Map.entry("sql", "sql"),
		Map.entry("xml", "xml"), Map.entry("html", "xml"), Map.entry("htm", "xml"), Map.entry("xhtml", "xml"),
		Map.entry("svg", "xml"),
		Map.entry("css", "css"), Map.entry("scss", "scss"), Map.entry("less", "less"),
		Map.entry("json", "json"),
		Map.entry("yaml", "yaml"), Map.entry("yml", "yaml"),
		Map.entry("toml", "ini"), Map.entry("ini", "ini"), Map.entry("cfg", "ini"), Map.entry("properties", "ini"),
		Map.entry("md", "markdown"), Map.entry("markdown", "markdown"),
		Map.entry("diff", "diff"), Map.entry("patch", "diff"),
		Map.entry("mk", "makefile"));

	/**
	 * Maps a file path to a highlight.js language identifier, or {@code null} when the extension is unknown so the
	 * blob is rendered as plain text (no highlighter loaded). Kept explicit rather than relying on highlight.js
	 * auto-detection, which is unreliable on short snippets.
	 */
	static String highlightLanguage(String path)
	{
		int slash = path.lastIndexOf('/');
		String name = (slash < 0 ? path : path.substring(slash + 1)).toLowerCase(Locale.ROOT);
		// extensionless / dotted well-known filenames
		if (name.equals("dockerfile") || name.startsWith("dockerfile."))
		{
			return "dockerfile";
		}
		if (name.equals("makefile"))
		{
			return "makefile";
		}
		int dot = name.lastIndexOf('.');
		String ext = dot <= 0 ? "" : name.substring(dot + 1);
		return HIGHLIGHT_BY_EXTENSION.get(ext);
	}

	/** Distinct highlight.js language ids this mapping can emit — used by tests to assert each has a bundled grammar. */
	static Set<String> highlightLanguages()
	{
		Set<String> languages = new TreeSet<>(HIGHLIGHT_BY_EXTENSION.values());
		languages.add("dockerfile");
		languages.add("makefile");
		return languages;
	}

	/** A single segment of the path breadcrumb. {@code href} is {@code null} for the current location (rendered plain). */
	public record Crumb(String label, String href)
	{
	}

	/**
	 * Builds the path breadcrumb for a tree or blob view: the ref, then one crumb per path segment. Every crumb links
	 * to its directory tree except the last, which is the current location and is rendered as plain text.
	 */
	private static List<Crumb> breadcrumbs(Repository repo, String ref, String path)
	{
		String treeBase = "/repos/" + repo.owner.username + "/" + repo.name + "/tree/" + ref;
		List<Crumb> crumbs = new ArrayList<>();
		crumbs.add(new Crumb(ref, treeBase));
		if (path != null && !path.isEmpty())
		{
			StringBuilder cumulative = new StringBuilder();
			for (String segment : path.split("/"))
			{
				if (segment.isEmpty())
				{
					continue;
				}
				cumulative.append('/').append(segment);
				crumbs.add(new Crumb(segment, treeBase + cumulative));
			}
		}
		// the current location is not a link
		Crumb current = crumbs.get(crumbs.size() - 1);
		crumbs.set(crumbs.size() - 1, new Crumb(current.label(), null));
		return crumbs;
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

	private Repository requireOwner(String owner, String name)
	{
		Repository repo = requireReadable(owner, name);
		User user = currentUser.get();
		if (user == null || !user.id.equals(repo.owner.id))
		{
			// hide existence from non-owners rather than signalling 403
			throw new NotFoundException();
		}
		return repo;
	}

}
