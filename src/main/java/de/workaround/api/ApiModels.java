package de.workaround.api;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import de.workaround.model.Issue;
import de.workaround.model.IssueComment;
import de.workaround.model.MergeRequest;
import de.workaround.model.MergeRequestComment;
import de.workaround.model.Repository;
import de.workaround.model.User;

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
		Instant createdAt, String parentOwner, String parentName)
	{
		/** Projection with the parent hidden — safe default for listings where the viewer's read access is unknown. */
		public static RepositoryView of(Repository repo)
		{
			return of(repo, false);
		}

		/**
		 * @param showParent whether the caller may see the fork's parent; when false (or the repo is not a
		 *   fork) {@code parentOwner}/{@code parentName} are null, so a source turned private is never
		 *   disclosed through its forks.
		 */
		public static RepositoryView of(Repository repo, boolean showParent)
		{
			boolean parent = showParent && repo.parent != null;
			return new RepositoryView(repo.ownerHandle(), repo.name, repo.visibility, repo.description,
				repo.createdAt, parent ? repo.parent.ownerHandle() : null, parent ? repo.parent.name : null);
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
		String targetBranch, MergeRequest.Status status, String author, String assignee, String reviewer,
		Instant createdAt, Instant mergedAt)
	{
		public static MergeRequestView of(MergeRequest mr)
		{
			return new MergeRequestView(mr.number, mr.title, mr.description, mr.sourceBranch, mr.targetBranch,
				mr.status, mr.author.username, mr.assignee == null ? null : mr.assignee.username,
				mr.reviewer == null ? null : mr.reviewer.username, mr.createdAt, mr.mergedAt);
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

	public record IssueCommentView(String id, String body, String author, Instant createdAt)
	{
		public static IssueCommentView of(IssueComment comment)
		{
			return new IssueCommentView(comment.id.toString(), comment.body, comment.author.username,
				comment.createdAt);
		}
	}

	/**
	 * A user in the Gitea contract. Renovate (and other Gitea clients) read {@code login} as the handle and
	 * {@code full_name}/{@code email} for commit metadata; {@code username} is emitted too as Gitea's historical
	 * alias for {@code login}. Null display name/email surface as empty strings so consumers never NPE on them.
	 */
	public record UserView(long id, String login, String username, @JsonProperty("full_name") String fullName,
		String email)
	{
		public static UserView of(User user)
		{
			return new UserView(GiteaIds.of(user.id), user.username, user.username,
				user.displayName == null ? "" : user.displayName, user.email == null ? "" : user.email);
		}
	}

	public record VersionView(String version)
	{
	}

	/**
	 * A person in a listing where the caller's read access is unknown (search is open to anonymous callers).
	 * Deliberately omits {@code email}: unlike the self-scoped {@code /api/v1/user}, a search response would
	 * otherwise disclose every matched user's address to unauthenticated callers.
	 */
	public record PersonView(long id, String login, String username, @JsonProperty("full_name") String fullName)
	{
		public static PersonView of(User user)
		{
			return new PersonView(GiteaIds.of(user.id), user.username, user.username,
				user.displayName == null ? "" : user.displayName);
		}
	}

	public record SearchView(List<RepositoryView> repositories, List<PersonView> persons)
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
