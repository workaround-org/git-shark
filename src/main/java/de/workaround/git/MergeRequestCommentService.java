package de.workaround.git;

import java.util.List;

import de.workaround.model.MergeRequest;
import de.workaround.model.MergeRequestComment;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Manages line-level review comments on merge-request diffs. Any authenticated user who can read the repository
 * may comment; a comment can be removed by its author or by the repository owner. Comments must anchor to a line
 * that is actually part of the merge request's current diff, so they cannot be orphaned on creation.
 */
@ApplicationScoped
public class MergeRequestCommentService
{
	@Inject
	MergeRequestComment.Repo comments;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	MergeRequestService mergeRequests;

	@Transactional
	public MergeRequestComment add(User actor, MergeRequest mr, String filePath, int oldLine, int newLine, String body)
	{
		requireReader(actor, mr);
		String trimmedBody = body == null ? "" : body.strip();
		if (trimmedBody.isEmpty())
		{
			throw new InvalidMergeRequestException("Comment must not be empty");
		}
		if (filePath == null || filePath.isBlank())
		{
			throw new InvalidMergeRequestException("Comment must reference a file");
		}
		if (!anchorExists(mr, filePath, oldLine, newLine))
		{
			throw new InvalidMergeRequestException("Comment does not anchor to a line in the diff");
		}
		MergeRequestComment comment = new MergeRequestComment();
		comment.mergeRequest = mr;
		comment.author = actor;
		comment.filePath = filePath;
		comment.oldLine = oldLine;
		comment.newLine = newLine;
		comment.body = trimmedBody;
		comment.persist();
		return comment;
	}

	public List<MergeRequestComment> list(MergeRequest mr)
	{
		return comments.findByMergeRequest(mr);
	}

	@Transactional
	public void delete(User actor, MergeRequestComment comment)
	{
		if (actor == null)
		{
			throw new ForbiddenOperationException("Authentication required");
		}
		MergeRequestComment managed = comments.findById(comment.id);
		if (managed == null)
		{
			return;
		}
		boolean isAuthor = managed.author.id.equals(actor.id);
		boolean isOwner = accessPolicy.canWrite(actor, managed.mergeRequest.repository);
		if (!isAuthor && !isOwner)
		{
			throw new ForbiddenOperationException("Only the comment author or the repository owner can delete it");
		}
		comments.deleteById(managed.id);
	}

	private void requireReader(User actor, MergeRequest mr)
	{
		if (actor == null)
		{
			throw new ForbiddenOperationException("Authentication required");
		}
		if (!accessPolicy.canRead(actor, mr.repository))
		{
			throw new ForbiddenOperationException("You cannot comment on this merge request");
		}
	}

	private boolean anchorExists(MergeRequest mr, String filePath, int oldLine, int newLine)
	{
		return mergeRequests.diff(mr)
			.map(diff -> diff.files().stream()
				.filter(file -> file.path().equals(filePath))
				.flatMap(file -> file.lines().stream())
				.anyMatch(line -> line.oldLine() == oldLine && line.newLine() == newLine
					&& ("add".equals(line.type()) || "del".equals(line.type()) || "context".equals(line.type()))))
			.orElse(false);
	}

}
