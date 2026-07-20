package de.workaround.web;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.CollaboratorService;
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
			UUID currentUserId, boolean canModerate, MergeRequest mr, List<FileDiffView> files, int additions,
			int deletions, List<User> assignees, List<MergeRequestComment> discussion);
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
	CollaboratorService collaboratorService;

	@Inject
	User.Repo userRepo;

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
		return Response.seeOther(detailUri(repo, mr.number)).build();
	}

	@GET
	@jakarta.ws.rs.Path("{number:\\d+}")
	public TemplateInstance detail(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, number).orElseThrow(NotFoundException::new);
		GitMergeService.DiffView diff = mergeRequestService.diff(mr).orElse(null);
		List<MergeRequestComment> comments = commentService.list(mr);
		// line-anchored review comments carry a file path; general discussion comments have none
		List<MergeRequestComment> lineComments = comments.stream().filter(c -> c.filePath != null).toList();
		List<MergeRequestComment> discussion = comments.stream().filter(c -> c.filePath == null).toList();
		User user = currentUser.get();
		boolean loggedIn = user != null;
		UUID currentUserId = user == null ? null : user.id;
		// comment moderation (delete any comment) follows write access: owner, collaborator or org member
		boolean canModerate = accessPolicy.canWrite(user, repo);

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
					List<MergeRequestComment> anchored = commentable
						? lineComments.stream()
							.filter(c -> c.filePath.equals(file.path()) && c.oldLine == line.oldLine()
								&& c.newLine == line.newLine())
							.toList()
						: List.of();
					String anchorId = "cl-" + fileIndex + "-" + lineIndex;
					lines.add(new DiffLineView(anchorId, line, commentable && loggedIn, anchored));
					lineIndex++;
				}
				files.add(new FileDiffView(file.path(), file.changeType(), file.additions(), file.deletions(), lines));
				additions += file.additions();
				deletions += file.deletions();
				fileIndex++;
			}
		}
		return Templates.mergeRequest(repo, repoNav.build(repo, uriInfo), isOwner(repo), loggedIn, currentUserId,
			canModerate, mr, files, additions, deletions, assignableUsers(repo), discussion);
	}

	/** How many of a repository's top commit authors the pickers offer as suggestions. */
	private static final int TOP_CONTRIBUTORS = 5;

	/**
	 * Suggestions offered by the assignee/reviewer pickers: the repository owner (for personal repos), every
	 * collaborator, and the repository's most prolific commit authors — a reviewer is often best drawn from
	 * the people who actually wrote the code. Assignment itself accepts any username; this list only powers
	 * the picker menus.
	 */
	private List<User> assignableUsers(Repository repo)
	{
		List<User> users = new ArrayList<>();
		if (repo.ownerUser != null)
		{
			users.add(repo.ownerUser);
		}
		collaboratorService.list(repo).forEach(collaborator -> users.add(collaborator.user));
		appendTopContributors(repo, users);
		return users;
	}

	/**
	 * Appends the repository's top commit authors whose email maps to a platform account and who are not
	 * already suggested. Only authors on the default branch are considered; empty repositories add nothing.
	 */
	private void appendTopContributors(Repository repo, List<User> users)
	{
		Path path = service.repositoryPath(repo);
		if (browse.isEmpty(path))
		{
			return;
		}
		Set<UUID> present = users.stream().map(user -> user.id).collect(Collectors.toSet());
		for (GitBrowseService.Contributor contributor : browse.contributors(path, browse.defaultBranch(path),
			TOP_CONTRIBUTORS))
		{
			if (contributor.email() == null || contributor.email().isBlank())
			{
				continue;
			}
			userRepo.findByEmailIgnoreCase(contributor.email()).ifPresent(user ->
			{
				if (user.username != null && present.add(user.id))
				{
					users.add(user);
				}
			});
		}
	}

	/** Merge requests were originally addressed by UUID; keep old bookmarks and federated links working. */
	@GET
	@jakarta.ws.rs.Path("{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}")
	public Response legacyDetail(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") UUID id)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, id).orElseThrow(NotFoundException::new);
		return Response.status(Response.Status.MOVED_PERMANENTLY).location(detailUri(repo, mr.number)).build();
	}

	@POST
	@jakarta.ws.rs.Path("{number:\\d+}/comments")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response comment(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number, @FormParam("filePath") String filePath,
		@FormParam("oldLine") @DefaultValue("-1") int oldLine, @FormParam("newLine") @DefaultValue("-1") int newLine,
		@FormParam("body") String body)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, number).orElseThrow(NotFoundException::new);
		commentService.add(currentUser.require(), mr, filePath, oldLine, newLine, body);
		return Response.seeOther(detailUri(repo, mr.number)).build();
	}

	@POST
	@jakarta.ws.rs.Path("{number:\\d+}/discussion")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response discuss(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number, @FormParam("body") String body)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, number).orElseThrow(NotFoundException::new);
		commentService.addGeneral(currentUser.require(), mr, body);
		return Response.seeOther(detailUri(repo, mr.number)).build();
	}

	@POST
	@jakarta.ws.rs.Path("{number:\\d+}/comments/{commentId}/delete")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response deleteComment(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number, @PathParam("commentId") String commentId)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, number).orElseThrow(NotFoundException::new);
		MergeRequestComment comment = commentRepo.findById(parseId(commentId));
		if (comment == null || !comment.mergeRequest.id.equals(mr.id))
		{
			throw new NotFoundException();
		}
		commentService.delete(currentUser.require(), comment);
		return Response.seeOther(detailUri(repo, mr.number)).build();
	}

	private static boolean isContent(String lineType)
	{
		return "add".equals(lineType) || "del".equals(lineType) || "context".equals(lineType);
	}

	@POST
	@jakarta.ws.rs.Path("{number:\\d+}/merge")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response merge(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, number).orElseThrow(NotFoundException::new);
		mergeRequestService.merge(currentUser.require(), mr);
		return Response.seeOther(detailUri(repo, mr.number)).build();
	}

	@POST
	@jakarta.ws.rs.Path("{number:\\d+}/assign")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response assign(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number, @FormParam("assignee") String assignee)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, number).orElseThrow(NotFoundException::new);
		// username resolution/validation lives in the service (InvalidMergeRequestException -> 400 via mapper)
		mergeRequestService.assign(currentUser.require(), mr, assignee);
		return Response.seeOther(detailUri(repo, mr.number)).build();
	}

	@POST
	@jakarta.ws.rs.Path("{number:\\d+}/reviewer")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response reviewer(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number, @FormParam("reviewer") String reviewer)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, number).orElseThrow(NotFoundException::new);
		mergeRequestService.setReviewer(currentUser.require(), mr, reviewer);
		return Response.seeOther(detailUri(repo, mr.number)).build();
	}

	@POST
	@jakarta.ws.rs.Path("{number:\\d+}/close")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response close(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		Repository repo = requireReadable(owner, name);
		MergeRequest mr = mergeRequestService.find(repo, number).orElseThrow(NotFoundException::new);
		mergeRequestService.close(currentUser.require(), mr);
		return Response.seeOther(detailUri(repo, mr.number)).build();
	}

	private URI detailUri(Repository repo, int number)
	{
		return URI.create("/repos/" + repo.ownerHandle() + "/" + repo.name + "/merge-requests/" + number);
	}

	private boolean isOwner(Repository repo)
	{
		User user = currentUser.get();
		return accessPolicy.canAdmin(user, repo);
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
