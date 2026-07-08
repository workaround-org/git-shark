package de.workaround.web;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.GitBrowseService;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.IssueService;
import de.workaround.git.MergeRequestService;
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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@jakarta.ws.rs.Path("/repos/{owner}/{name}")
@Produces(MediaType.TEXT_HTML)
public class RepositoryResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance overview(Repository repo, boolean owner, boolean empty, String defaultBranch,
			List<GitBrowseService.TreeEntry> entries, String httpUrl, String sshUrl, boolean loggedIn,
			boolean pinned, GitBrowseService.CommitInfo latestCommit, String latestCommitAge, int commitCount,
			int branchCount, int tagCount, long openIssueCount, long openMrCount, String readmeName,
			String readmeHtml);

		static native TemplateInstance tree(Repository repo, String ref, String path,
			List<GitBrowseService.TreeEntry> entries, List<Crumb> crumbs, String activeTab);

		static native TemplateInstance blob(Repository repo, String ref, String path, boolean binary, String content,
			String language, List<Crumb> crumbs, String activeTab);

		static native TemplateInstance commits(Repository repo, String ref, List<GitBrowseService.CommitInfo> commits,
			int page, int prevPage, int nextPage, int size, boolean hasNext, String activeTab);

		static native TemplateInstance branches(Repository repo, List<GitBrowseService.BranchInfo> branches,
			List<String> tags, String activeTab, String tabRef);
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
	IssueService issueService;

	@Inject
	MergeRequestService mergeRequestService;

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
		boolean loggedIn = user != null;
		boolean pinned = loggedIn && pinService.isPinned(user, repo);
		GitBrowseService.CommitInfo latestCommit = empty ? null
			: browse.commits(path, defaultBranch, 0, 1)
				.filter(commitPage -> !commitPage.commits().isEmpty())
				.map(commitPage -> commitPage.commits().get(0))
				.orElse(null);
		String latestCommitAge = latestCommit == null ? null : relativeAge(latestCommit.date());
		int commitCount = empty ? 0 : browse.commitCount(path, defaultBranch);
		int branchCount = browse.branches(path).size();
		int tagCount = browse.tags(path).size();
		long openIssueCount = issueService.countOpen(repo);
		long openMrCount = mergeRequestService.countOpen(repo);
		GitBrowseService.TreeEntry readmeEntry = findReadme(entries);
		String readmeName = readmeEntry == null ? null : readmeEntry.name();
		String readmeHtml = readmeEntry == null ? null : browse.blob(path, defaultBranch, readmeEntry.path())
			.filter(blob -> !blob.binary())
			.map(blob -> renderMarkdown(new String(blob.content(), StandardCharsets.UTF_8)))
			.orElse(null);
		return Templates.overview(repo, isOwner, empty, defaultBranch, entries, httpUrl(repo), sshUrl(repo),
			loggedIn, pinned, latestCommit, latestCommitAge, commitCount, branchCount, tagCount, openIssueCount,
			openMrCount, readmeName, readmeHtml);
	}

	// README file names the overview looks for, in order of preference (matched case-insensitively).
	private static final List<String> README_NAMES = List.of("readme.md", "readme.markdown", "readme", "readme.txt");

	private static final Parser MARKDOWN_PARSER = Parser.builder().build();

	// escapeHtml + sanitizeUrls: README content is untrusted user input rendered into our page,
	// so raw HTML blocks are escaped and javascript:/data: link targets are stripped.
	private static final HtmlRenderer MARKDOWN_RENDERER = HtmlRenderer.builder()
		.escapeHtml(true)
		.sanitizeUrls(true)
		.build();

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

	static String renderMarkdown(String markdown)
	{
		return MARKDOWN_RENDERER.render(MARKDOWN_PARSER.parse(markdown));
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
		Path repoPath = service.repositoryPath(repo);
		return browse.listTree(repoPath, ref, path)
			.map(entries -> Templates.tree(repo, ref, path, entries, breadcrumbs(repo, ref, path), "files"))
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
			boundedSize, commitPage.hasNext(), "commits");
	}

	@GET
	@jakarta.ws.rs.Path("branches")
	public TemplateInstance branches(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		Path path = service.repositoryPath(repo);
		String tabRef = browse.isEmpty(path) ? null : browse.defaultBranch(path);
		return Templates.branches(repo, browse.branches(path), browse.tags(path), "branches", tabRef);
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

	private TemplateInstance blobView(Repository repo, Path repoPath, String ref, String path)
	{
		GitBrowseService.BlobView blob = browse.blob(repoPath, ref, path).orElseThrow(NotFoundException::new);
		String content = blob.binary() ? null : new String(blob.content(), StandardCharsets.UTF_8);
		String language = blob.binary() ? null : highlightLanguage(path);
		return Templates.blob(repo, ref, path, blob.binary(), content, language, breadcrumbs(repo, ref, path), "files");
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
