package de.workaround.git;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import de.workaround.model.Issue;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Manages per-repository issues. Reading follows the repository's read-visibility rule (enforced by
 * callers); creating, transitioning and deleting issues require write access, i.e. repository ownership.
 */
@ApplicationScoped
public class IssueService
{
	@Inject
	Issue.Repo issues;

	@Inject
	AccessPolicy accessPolicy;

	@Transactional
	public Issue create(User actor, Repository repository, String title, String description)
	{
		requireWrite(actor, repository);
		String trimmedTitle = title == null ? "" : title.strip();
		if (trimmedTitle.isEmpty())
		{
			throw new InvalidIssueException("Issue title must not be empty");
		}
		Issue issue = new Issue();
		issue.repository = repository;
		issue.author = actor;
		issue.number = issues.maxNumber(repository) + 1;
		issue.title = trimmedTitle;
		issue.description = description == null || description.isBlank() ? null : description.strip();
		issue.status = Issue.Status.PLANNED;
		issue.persist();
		return issue;
	}

	public List<Issue> list(Repository repository)
	{
		return issues.findByRepository(repository);
	}

	/** Number of open issues (PLANNED or IN_DEVELOPMENT) in the repository; DONE issues are excluded. */
	public long countOpen(Repository repository)
	{
		return issues.countOpen(repository);
	}

	public Optional<Issue> find(Repository repository, UUID id)
	{
		return issues.findByRepositoryAndId(repository, id);
	}

	@Transactional
	public void updateStatus(User actor, Issue issue, Issue.Status status)
	{
		requireWrite(actor, issue.repository);
		// re-attach: the issue may have been loaded in a previous request/transaction. It may also have
		// been deleted concurrently since then, so guard against a missing row (findById returns null).
		Issue managed = issues.findById(issue.id);
		if (managed != null)
		{
			managed.status = status;
		}
	}

	@Transactional
	public void delete(User actor, Issue issue)
	{
		requireWrite(actor, issue.repository);
		issues.deleteById(issue.id);
	}

	/**
	 * Closes (moves to DONE) the issue with the given number in the repository, if it exists and is still open.
	 * Called from the post-receive path when a pushed commit references the issue with a closing keyword; the push
	 * itself was already write-authorized by the transport, so no per-actor check is repeated here. Idempotent.
	 */
	@Transactional
	public void markDoneByNumber(Repository repository, int number)
	{
		issues.findByRepositoryAndNumber(repository, number)
			.filter(issue -> issue.status != Issue.Status.DONE)
			.ifPresent(issue -> issue.status = Issue.Status.DONE);
	}

	private void requireWrite(User actor, Repository repository)
	{
		if (!accessPolicy.canWrite(actor, repository))
		{
			throw new ForbiddenOperationException("Only the repository owner can manage issues");
		}
	}

}
