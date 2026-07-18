package de.workaround.git;

import java.util.List;

import de.workaround.model.Issue;
import de.workaround.model.IssueComment;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Manages free-text discussion comments on issues. Any authenticated user who can read the repository may
 * comment; a comment can be removed by its author, the repository owner, or a collaborator.
 */
@ApplicationScoped
public class IssueCommentService
{
	@Inject
	IssueComment.Repo comments;

	@Inject
	AccessPolicy accessPolicy;

	@Transactional
	public IssueComment add(User actor, Issue issue, String body)
	{
		requireReader(actor, issue);
		String trimmedBody = body == null ? "" : body.strip();
		if (trimmedBody.isEmpty())
		{
			throw new InvalidIssueException("Comment must not be empty");
		}
		IssueComment comment = new IssueComment();
		comment.issue = issue;
		comment.author = actor;
		comment.body = trimmedBody;
		comment.persist();
		return comment;
	}

	public List<IssueComment> list(Issue issue)
	{
		return comments.findByIssue(issue);
	}

	@Transactional
	public void delete(User actor, IssueComment comment)
	{
		if (actor == null)
		{
			throw new ForbiddenOperationException("Authentication required");
		}
		IssueComment managed = comments.findById(comment.id);
		if (managed == null)
		{
			return;
		}
		boolean isAuthor = managed.author.id.equals(actor.id);
		boolean isOwner = accessPolicy.canWrite(actor, managed.issue.repository);
		if (!isAuthor && !isOwner)
		{
			throw new ForbiddenOperationException("Only the comment author, the repository owner or a collaborator can delete it");
		}
		comments.deleteById(managed.id);
	}

	private void requireReader(User actor, Issue issue)
	{
		if (actor == null)
		{
			throw new ForbiddenOperationException("Authentication required");
		}
		if (!accessPolicy.canRead(actor, issue.repository))
		{
			throw new ForbiddenOperationException("You cannot comment on this issue");
		}
	}

}
