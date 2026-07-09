package de.workaround.web;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitBrowseService;
import de.workaround.git.GitMergeService;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.MergeRequestCommentService;
import de.workaround.git.MergeRequestService;
import de.workaround.model.MergeRequest;
import de.workaround.model.MergeRequestComment;
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
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@jakarta.ws.rs.Path("/repos/{owner}/{name}/merge-requests")
@Produces(MediaType.TEXT_HTML)
public class MergeRequestResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance mergeRequests(Repository repo, RepoNav nav, boolean owner,
			List<MergeRequest> open, List<MergeRequest> closed);

		static native TemplateInstance newMergeRequest(Repository repo, RepoNav nav, List<String> branches,
			String defaultBranch);

		static native TemplateInstance mergeRequest(Repository repo, RepoNav nav, boolean owner, boolean loggedIn,
			UUID currentUserId, MergeRequest mr, List<FileDiffView> files, int additions, int deletions);
	}

	/**
	 * A diff line paired with a page-unique id (for the no-JS comment toggle), whether it accepts comments, and the
	 * comments already anchored to it.
	 */
	public record DiffLineView(String anchorId, GitMergeService.DiffLine line, boolean commentable,
		List<MergeRequestComment> comments)
	{
	}

	/** A changed file's diff lines augmented with per-line comment state, for the merge-request detail view. */
	public record FileDiffView(String path, String changeType, int additions, int deletions, List<DiffLineView> lines)
	{
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
	MergeRequestService mergeRequestService;

	@Inject
	MergeRequestCommentService commentService;

	@Inject
	MergeRequestComment.Repo commentRepo;

	@Inject
	RepoNavService repoNav;

	@Context
	UriInfo uriInfo;

	@GET
	public TemplateInstance list(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		List<MergeRequest> all = mergeRequestService.list(repo);
		List<MergeRequest> open = all.stream().filter(mr -> mr.status == MergeRequest.Status.OPEN).toList();
		List<MergeRequest> closed = all.stream().filter(mr -> mr.status != MergeRequest.Status.OPEN).toList();
		return Templates.mergeRequests(repo, repoNav.build(repo, uriInfo), isOwner(repo), open, closed);
	}

	@GET
	@jakarta.ws.rs.Path("new")
	public TemplateInstance newForm(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		if (!accessPolicy.canWrite(currentUser.get(), repo))
		{
			throw new ForbiddenOperationException("Only the repository owner or a collaborator can open merge requests");
		}
		Path path = service.repositoryPath(repo);
		List<String> branches = browse.branches(path).stream().map(GitBrowseService.BranchInfo::name).toList();
		String defaultBranch = browse.isEmpty(path) ? null : browse.defaultBranch(path);
		return Templates.newMergeRequest(repo, repoNav.build(repo, uriInfo), branches, defaultBranch);
	}

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response create(@PathParam("owner") String owner, @PathParam("name") String name,
		@FormParam("title") String title, @FormParam("description") String description,
		@FormParam("sourceBranch") String sourceBranch, @FormParam("targetBranch") String targetBranch)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.create(currentUser.require(), repo, title, description, sourceBranch,
			targetBranch);
		return Response.seeOther(detailUri(repo, mr.id)).build();
	}

	@GET
	@jakarta.ws.rs.Path("{id}")
	public TemplateInstance detail(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") String id)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, parseId(id)).orElseThrow(NotFoundException::new);
		GitMergeService.DiffView diff = mergeRequestService.diff(mr).orElse(null);
		List<MergeRequestComment> comments = commentService.list(mr);
		User user = currentUser.get();
		boolean loggedIn = user != null;
		UUID currentUserId = user == null ? null : user.id;

		List<FileDiffView> files = new ArrayList<>();
		int additions = 0;
		int deletions = 0;
		if (diff != null)
		{
			int fileIndex = 0;
			for (GitMergeService.FileDiff file : diff.files())
			{
				List<DiffLineView> lines = new ArrayList<>();
				int lineIndex = 0;
				for (GitMergeService.DiffLine line : file.lines())
				{
					boolean commentable = isContent(line.type());
					List<MergeRequestComment> lineComments = commentable
						? comments.stream()
							.filter(c -> c.filePath.equals(file.path()) && c.oldLine == line.oldLine()
								&& c.newLine == line.newLine())
							.toList()
						: List.of();
					String anchorId = "cl-" + fileIndex + "-" + lineIndex;
					lines.add(new DiffLineView(anchorId, line, commentable && loggedIn, lineComments));
					lineIndex++;
				}
				files.add(new FileDiffView(file.path(), file.changeType(), file.additions(), file.deletions(), lines));
				additions += file.additions();
				deletions += file.deletions();
				fileIndex++;
			}
		}
		return Templates.mergeRequest(repo, repoNav.build(repo, uriInfo), isOwner(repo), loggedIn, currentUserId, mr,
			files, additions, deletions);
	}

	@POST
	@jakarta.ws.rs.Path("{id}/comments")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response comment(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") String id, @FormParam("filePath") String filePath,
		@FormParam("oldLine") @DefaultValue("-1") int oldLine, @FormParam("newLine") @DefaultValue("-1") int newLine,
		@FormParam("body") String body)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, parseId(id)).orElseThrow(NotFoundException::new);
		commentService.add(currentUser.require(), mr, filePath, oldLine, newLine, body);
		return Response.seeOther(detailUri(repo, mr.id)).build();
	}

	@POST
	@jakarta.ws.rs.Path("{id}/comments/{commentId}/delete")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response deleteComment(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") String id, @PathParam("commentId") String commentId)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, parseId(id)).orElseThrow(NotFoundException::new);
		MergeRequestComment comment = commentRepo.findById(parseId(commentId));
		if (comment == null || !comment.mergeRequest.id.equals(mr.id))
		{
			throw new NotFoundException();
		}
		commentService.delete(currentUser.require(), comment);
		return Response.seeOther(detailUri(repo, mr.id)).build();
	}

	private static boolean isContent(String lineType)
	{
		return "add".equals(lineType) || "del".equals(lineType) || "context".equals(lineType);
	}

	@POST
	@jakarta.ws.rs.Path("{id}/merge")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response merge(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") String id)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, parseId(id)).orElseThrow(NotFoundException::new);
		mergeRequestService.merge(currentUser.require(), mr);
		return Response.seeOther(detailUri(repo, mr.id)).build();
	}

	@POST
	@jakarta.ws.rs.Path("{id}/close")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response close(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") String id)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, parseId(id)).orElseThrow(NotFoundException::new);
		mergeRequestService.close(currentUser.require(), mr);
		return Response.seeOther(detailUri(repo, mr.id)).build();
	}

	private URI detailUri(Repository repo, UUID id)
	{
		return URI.create("/repos/" + repo.owner.username + "/" + repo.name + "/merge-requests/" + id);
	}

	private boolean isOwner(Repository repo)
	{
		User user = currentUser.get();
		return user != null && user.id.equals(repo.owner.id);
	}

	private static UUID parseId(String id)
	{
		try
		{
			return UUID.fromString(id);
		}
		catch (IllegalArgumentException malformed)
		{
			throw new NotFoundException();
		}
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

}
