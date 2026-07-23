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

	/** A repository owner (user or organisation) as Gitea's nested owner object; clients read {@code login}. */
	public record OwnerView(long id, String login, String username)
	{
		public static OwnerView of(Repository repo)
		{
			return new OwnerView(GiteaIds.of(repo.ownerId()), repo.ownerHandle(), repo.ownerHandle());
		}
	}

	/** The caller's effective rights on a repository; Gitea clients gate writes (e.g. automerge) on {@code push}. */
	public record PermissionsView(boolean admin, boolean push, boolean pull)
	{
	}

	/**
	 * A repository in the Gitea contract. git-shark has no archive feature and its push-mirrors are outbound,
	 * so {@code archived}/{@code mirror} are always false; only merge-commit merges are implemented, so the
	 * {@code allow_*} flags advertise merge commits only. {@code cloneUrl}/{@code htmlUrl}/{@code defaultBranch}
	 * are supplied by the caller because they need the request's external base URL and a live git read.
	 */
	public record RepositoryView(long id, String name, @JsonProperty("full_name") String fullName, OwnerView owner,
		String description, @JsonProperty("private") boolean isPrivate, boolean fork, boolean mirror,
		boolean archived, boolean empty, @JsonProperty("default_branch") String defaultBranch,
		@JsonProperty("clone_url") String cloneUrl, @JsonProperty("html_url") String htmlUrl, RepositoryView parent,
		PermissionsView permissions, @JsonProperty("allow_merge_commits") boolean allowMergeCommits,
		@JsonProperty("allow_rebase") boolean allowRebase, @JsonProperty("allow_squash_merge") boolean allowSquashMerge,
		@JsonProperty("default_merge_style") String defaultMergeStyle, Instant createdAt)
	{
		/**
		 * A shallow projection used only as a fork's {@code parent}: DB-derived fields only, no git read or URLs.
		 * Its own parent is always null so the shape never recurses.
		 */
		public static RepositoryView shallow(Repository repo)
		{
			return new RepositoryView(GiteaIds.of(repo.id), repo.name, repo.ownerHandle() + "/" + repo.name,
				OwnerView.of(repo), repo.description, repo.visibility == Repository.Visibility.PRIVATE,
				repo.parent != null, false, false, false, null, null, null, null, null, true, false, false, "merge",
				repo.createdAt);
		}

		/**
		 * @param showParent whether the caller may see this fork's parent; when false (or the repo is not a
		 *   fork) {@code parent} is null, so a source turned private is never disclosed through its forks.
		 */
		public static RepositoryView of(Repository repo, boolean showParent, boolean empty, String defaultBranch,
			String cloneUrl, String htmlUrl, PermissionsView permissions)
		{
			boolean fork = repo.parent != null;
			RepositoryView parent = showParent && fork ? shallow(repo.parent) : null;
			return new RepositoryView(GiteaIds.of(repo.id), repo.name, repo.ownerHandle() + "/" + repo.name,
				OwnerView.of(repo), repo.description, repo.visibility == Repository.Visibility.PRIVATE, fork, false,
				false, empty, defaultBranch, cloneUrl, htmlUrl, parent, permissions, true, false, false, "merge",
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

	/**
	 * A merge request projected as a Gitea pull request. State maps {@code OPEN→open} and
	 * {@code MERGED/CLOSED→closed}, with a separate {@code merged} flag; {@code head}/{@code base} carry the
	 * source/target branches. {@code user}/{@code assignee} use the email-free {@link PersonView} because a
	 * public repository's pulls are world-readable. git-shark stores no update or close timestamp, so
	 * {@code updated_at} mirrors {@code created_at} and {@code closed_at} falls back to it for closed pulls.
	 */
	public record PullView(long id, int number, String title, String body, String state, boolean merged,
		BranchRef base, BranchRef head, PersonView user, PersonView assignee, List<Object> labels, boolean mergeable,
		@JsonProperty("created_at") Instant createdAt, @JsonProperty("updated_at") Instant updatedAt,
		@JsonProperty("closed_at") Instant closedAt, @JsonProperty("merged_at") Instant mergedAt)
	{
		public record BranchRef(String ref, String label)
		{
		}

		public static PullView of(MergeRequest mr)
		{
			boolean open = mr.status == MergeRequest.Status.OPEN;
			boolean merged = mr.status == MergeRequest.Status.MERGED;
			String owner = mr.repository.ownerHandle();
			BranchRef base = new BranchRef(mr.targetBranch, owner + ":" + mr.targetBranch);
			BranchRef head = new BranchRef(mr.sourceBranch, owner + ":" + mr.sourceBranch);
			Instant closedAt = open ? null : mr.mergedAt != null ? mr.mergedAt : mr.createdAt;
			return new PullView(GiteaIds.of(mr.id), mr.number, mr.title, mr.description, open ? "open" : "closed",
				merged, base, head, PersonView.of(mr.author), mr.assignee == null ? null : PersonView.of(mr.assignee),
				List.of(), open, mr.createdAt, mr.createdAt, closedAt, mr.mergedAt);
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
	 * A branch in the Gitea contract. Clients (Renovate) read {@code commit.id} as the branch tip SHA.
	 * git-shark has no branch protection, so {@code protected} is always false.
	 */
	public record BranchView(String name, CommitRef commit, @JsonProperty("protected") boolean isProtected)
	{
		public record CommitRef(String id)
		{
		}
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

	/** Gitea pull-creation payload: {@code head}/{@code base} are branch names, {@code body} the description. */
	public record NewPull(String title, String body, String head, String base)
	{
	}

	/** Gitea pull edit: any null field is left unchanged; {@code state} is {@code open} or {@code closed}. */
	public record PullEdit(String title, String body, String state)
	{
	}

	/**
	 * A Gitea commit status. The list/combined endpoints report one status per CI run on the commit;
	 * {@code POST /statuses/{sha}} still only echoes the posted status back (git-shark's statuses come
	 * from its own runs, not external posts).
	 */
	public record CommitStatusView(long id, String state, String context, String description,
		@JsonProperty("target_url") String targetUrl)
	{
	}

	/**
	 * The Gitea combined commit status: the worst-of the commit's CI runs (mapped to {@code success}/
	 * {@code failure}/{@code pending}). A commit with no runs reports {@code success} (via {@link
	 * #allClear}) so a Gitea client like Renovate treats the ref as passing and proceeds.
	 */
	public record CombinedStatusView(String state, @JsonProperty("worstStatus") String worstStatus, String sha,
		@JsonProperty("total_count") int totalCount, List<CommitStatusView> statuses)
	{
		public static CombinedStatusView allClear(String sha)
		{
			return new CombinedStatusView("success", "success", sha, 0, List.of());
		}
	}

	/** Gitea create-status payload posted to {@code /statuses/{sha}}. */
	public record NewStatus(String state, String context, String description,
		@JsonProperty("target_url") String targetUrl)
	{
	}

	public record NewComment(String filePath, int oldLine, int newLine, String body)
	{
	}
}
