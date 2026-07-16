package de.workaround.git;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.workaround.model.MergeRequest;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Manages per-repository merge requests. Reading follows the repository's read-visibility rule (enforced by
 * callers); creating, merging and closing require write access, i.e. repository ownership. The proposed changes
 * are never stored — they are diffed live from git through {@link GitMergeService}.
 */
@ApplicationScoped
public class MergeRequestService
{
	@Inject
	MergeRequest.Repo mergeRequests;

	@Inject
	User.Repo users;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	GitRepositoryService repositories;

	@Inject
	GitMergeService gitMerge;

	@Transactional
	public MergeRequest create(User actor, Repository repository, String title, String description,
		String sourceBranch, String targetBranch)
	{
		requireWrite(actor, repository);
		String trimmedTitle = title == null ? "" : title.strip();
		if (trimmedTitle.isEmpty())
		{
			throw new InvalidMergeRequestException("Merge request title must not be empty");
		}
		String source = normalizeBranch(sourceBranch);
		String target = normalizeBranch(targetBranch);
		if (source.isEmpty() || target.isEmpty())
		{
			throw new InvalidMergeRequestException("Source and target branch must be provided");
		}
		if (source.equals(target))
		{
			throw new InvalidMergeRequestException("Source and target branch must differ");
		}
		Path bare = repositories.repositoryPath(repository);
		if (!gitMerge.branchExists(bare, source))
		{
			throw new InvalidMergeRequestException("Unknown source branch: " + source);
		}
		if (!gitMerge.branchExists(bare, target))
		{
			throw new InvalidMergeRequestException("Unknown target branch: " + target);
		}

		MergeRequest mr = new MergeRequest();
		mr.repository = repository;
		mr.author = actor;
		mr.number = mergeRequests.maxNumber(repository) + 1;
		mr.title = trimmedTitle;
		mr.description = description == null || description.isBlank() ? null : description.strip();
		mr.sourceBranch = source;
		mr.targetBranch = target;
		mr.status = MergeRequest.Status.OPEN;
		mr.persist();
		return mr;
	}

	public List<MergeRequest> list(Repository repository)
	{
		return mergeRequests.findByRepository(repository);
	}

	/** Number of open merge requests in the repository; merged and closed ones are excluded. */
	public long countOpen(Repository repository)
	{
		return mergeRequests.countOpen(repository);
	}

	public Optional<MergeRequest> find(Repository repository, UUID id)
	{
		return mergeRequests.findByRepositoryAndId(repository, id);
	}

	public Optional<MergeRequest> find(Repository repository, int number)
	{
		return mergeRequests.findByRepositoryAndNumber(repository, number);
	}

	/** The live diff (merge-base..source) of the proposed change, or empty if a branch has since disappeared. */
	public Optional<GitMergeService.DiffView> diff(MergeRequest mr)
	{
		Path bare = repositories.repositoryPath(mr.repository);
		return gitMerge.diff(bare, mr.targetBranch, mr.sourceBranch);
	}

	/**
	 * Merges the request's source branch into its target and moves the request to MERGED. A merge conflict or a
	 * source already contained in the target is rejected with {@link InvalidMergeRequestException}; only OPEN
	 * requests can be merged.
	 */
	@Transactional
	public GitMergeService.MergeResult merge(User actor, MergeRequest mr)
	{
		requireWrite(actor, mr.repository);
		MergeRequest managed = mergeRequests.findById(mr.id);
		if (managed == null)
		{
			throw new InvalidMergeRequestException("Merge request no longer exists");
		}
		if (managed.status != MergeRequest.Status.OPEN)
		{
			throw new InvalidMergeRequestException("Only open merge requests can be merged");
		}
		Path bare = repositories.repositoryPath(managed.repository);
		String message = "Merge branch '" + managed.sourceBranch + "' into " + managed.targetBranch
			+ " (!" + managed.number + ")";
		GitMergeService.MergeResult result = gitMerge.merge(bare, managed.targetBranch, managed.sourceBranch,
			committerName(actor), committerEmail(actor), message);
		switch (result)
		{
			case MERGED, UP_TO_DATE ->
			{
				managed.status = MergeRequest.Status.MERGED;
				managed.mergedAt = Instant.now();
			}
			case CONFLICT -> throw new InvalidMergeRequestException(
				"Cannot merge automatically: " + managed.sourceBranch + " conflicts with " + managed.targetBranch);
			case MISSING_BRANCH -> throw new InvalidMergeRequestException(
				"Source or target branch no longer exists");
		}
		return result;
	}

	/**
	 * Assigns the merge request to the local user with the given username, or unassigns it when the
	 * username is blank/null. Any existing user can be named — assignment itself needs no repo access.
	 */
	@Transactional
	public void assign(User actor, MergeRequest mr, String username)
	{
		requireWrite(actor, mr.repository);
		User assignee = resolveUser(username);
		MergeRequest managed = mergeRequests.findById(mr.id);
		if (managed != null)
		{
			managed.assignee = assignee;
		}
	}

	/**
	 * Sets the merge request's reviewer to the local user with the given username, or clears it when the
	 * username is blank/null. Any existing user can be named — setting a reviewer needs no repo access.
	 */
	@Transactional
	public void setReviewer(User actor, MergeRequest mr, String username)
	{
		requireWrite(actor, mr.repository);
		User reviewer = resolveUser(username);
		MergeRequest managed = mergeRequests.findById(mr.id);
		if (managed != null)
		{
			managed.reviewer = reviewer;
		}
	}

	/** Resolves a username to a local user, or null when blank; an unknown handle is a 400-level error. */
	private User resolveUser(String username)
	{
		String handle = username == null ? "" : username.strip();
		if (handle.isEmpty())
		{
			return null;
		}
		return users.findByUsername(handle)
			.orElseThrow(() -> new InvalidMergeRequestException("No user with that username exists."));
	}

	/** Moves an open merge request to CLOSED without merging. */
	@Transactional
	public void close(User actor, MergeRequest mr)
	{
		requireWrite(actor, mr.repository);
		MergeRequest managed = mergeRequests.findById(mr.id);
		if (managed != null && managed.status == MergeRequest.Status.OPEN)
		{
			managed.status = MergeRequest.Status.CLOSED;
		}
	}

	private void requireWrite(User actor, Repository repository)
	{
		if (!accessPolicy.canWrite(actor, repository))
		{
			throw new ForbiddenOperationException("Only the repository owner or a collaborator can manage merge requests");
		}
	}

	private static String normalizeBranch(String branch)
	{
		return branch == null ? "" : branch.strip();
	}

	private static String committerName(User actor)
	{
		if (actor.displayName != null && !actor.displayName.isBlank())
		{
			return actor.displayName;
		}
		return actor.username;
	}

	private static String committerEmail(User actor)
	{
		if (actor.email != null && !actor.email.isBlank())
		{
			return actor.email;
		}
		return actor.username + "@git-shark.local";
	}

}
