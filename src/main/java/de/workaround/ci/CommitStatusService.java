package de.workaround.ci;

import java.util.List;
import java.util.Optional;

import de.workaround.model.ActionRun;
import de.workaround.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The overall CI status of a commit (issue #2, phase 3): the aggregate of every run for that commit
 * SHA, shown on commit and merge-request pages and exposed through the Gitea commit-status API.
 */
@ApplicationScoped
public class CommitStatusService
{
	@Inject
	ActionRun.Repo runs;

	public List<ActionRun> runsFor(Repository repository, String commitSha)
	{
		return runs.findByRepositoryAndCommitSha(repository, commitSha);
	}

	/**
	 * The aggregate status of a commit, or empty when it has no runs. Worst-of: any FAILURE →
	 * FAILURE; else anything still running/pending → RUNNING; else any CANCELLED → CANCELLED; else
	 * SUCCESS.
	 */
	public Optional<ActionRun.Status> aggregate(Repository repository, String commitSha)
	{
		return aggregate(runsFor(repository, commitSha));
	}

	public Optional<ActionRun.Status> aggregate(List<ActionRun> forCommit)
	{
		if (forCommit.isEmpty())
		{
			return Optional.empty();
		}
		if (forCommit.stream().anyMatch(r -> r.status == ActionRun.Status.FAILURE))
		{
			return Optional.of(ActionRun.Status.FAILURE);
		}
		if (forCommit.stream().anyMatch(r -> !r.status.isTerminal()))
		{
			return Optional.of(ActionRun.Status.RUNNING);
		}
		if (forCommit.stream().anyMatch(r -> r.status == ActionRun.Status.CANCELLED))
		{
			return Optional.of(ActionRun.Status.CANCELLED);
		}
		return Optional.of(ActionRun.Status.SUCCESS);
	}

	/** Map an aggregate run status to a Gitea commit-status state. */
	public static String toGiteaState(ActionRun.Status status)
	{
		return switch (status)
		{
			case SUCCESS -> "success";
			case FAILURE, CANCELLED -> "failure";
			case PENDING, RUNNING -> "pending";
		};
	}
}
