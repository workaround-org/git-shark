package de.workaround.api;

import java.time.Instant;
import java.util.List;

import de.workaround.model.Issue;
import de.workaround.model.MergeRequest;
import de.workaround.model.MergeRequestComment;
import de.workaround.model.Repository;

/**
 * JSON request and response shapes for the {@code /api/v1} surface. Response records are projected from
 * entities via their {@code of(...)} factories so the API contract never leaks lazy associations or
 * internal ids; request records are plain deserialization targets.
 */
public final class ApiModels
{
	private ApiModels()
	{
	}

	// -- responses --

	public record RepositoryView(String owner, String name, Repository.Visibility visibility, String description,
		Instant createdAt)
	{
		public static RepositoryView of(Repository repo)
		{
			return new RepositoryView(repo.ownerHandle(), repo.name, repo.visibility, repo.description,
				repo.createdAt);
		}
	}

	public record IssueView(int number, String title, String description, Issue.Status status, String author,
		String assignee, Instant createdAt)
	{
		public static IssueView of(Issue issue)
		{
			return new IssueView(issue.number, issue.title, issue.description, issue.status,
				issue.author.username, issue.assignee == null ? null : issue.assignee.username, issue.createdAt);
		}
	}

	public record MergeRequestView(int number, String title, String description, String sourceBranch,
		String targetBranch, MergeRequest.Status status, String author, Instant createdAt, Instant mergedAt)
	{
		public static MergeRequestView of(MergeRequest mr)
		{
			return new MergeRequestView(mr.number, mr.title, mr.description, mr.sourceBranch, mr.targetBranch,
				mr.status, mr.author.username, mr.createdAt, mr.mergedAt);
		}
	}

	public record CommentView(String id, String filePath, int oldLine, int newLine, String body, String author,
		Instant createdAt)
	{
		public static CommentView of(MergeRequestComment comment)
		{
			return new CommentView(comment.id.toString(), comment.filePath, comment.oldLine, comment.newLine,
				comment.body, comment.author.username, comment.createdAt);
		}
	}

	public record UserView(String username, String displayName)
	{
	}

	public record SearchView(List<RepositoryView> repositories, List<UserView> persons)
	{
	}

	// -- requests --

	public record NewRepository(String name, Repository.Visibility visibility, String description)
	{
	}

	public record NewIssue(String title, String description)
	{
	}

	public record IssueStatusUpdate(Issue.Status status)
	{
	}

	public record NewMergeRequest(String title, String description, String sourceBranch, String targetBranch)
	{
	}

	public record NewComment(String filePath, int oldLine, int newLine, String body)
	{
	}
}
