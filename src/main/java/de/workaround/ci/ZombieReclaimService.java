package de.workaround.ci;

import java.time.Instant;
import java.util.List;

import org.jboss.logging.Logger;

import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.CiRunner;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Fails tasks whose runner vanished mid-run (issue #2, phase 1). A claimed task carries a {@link
 * ActionTask#deadline}; once it passes with the task still RUNNING, the runner is presumed gone, so
 * the task is marked FAILURE, its run rolled up, and the runner flagged OFFLINE (or deleted, if it is
 * ephemeral). Runs on a schedule ({@code gitshark.ci.zombie-reclaim-interval}); {@link
 * #reclaim(Instant)} is the testable core.
 */
@ApplicationScoped
public class ZombieReclaimService
{
	private static final Logger LOG = Logger.getLogger(ZombieReclaimService.class);

	@Inject
	ActionTask.Repo tasks;

	@Inject
	CiRunner.Repo runners;

	@Inject
	TaskProgressService progress;

	@Scheduled(every = "{gitshark.ci.zombie-reclaim-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
	void sweep()
	{
		int reclaimed = reclaim(Instant.now());
		if (reclaimed > 0)
		{
			LOG.infof("Reclaimed %d zombie CI task(s) past their deadline", reclaimed);
		}
	}

	/** Fail every RUNNING task whose deadline is before {@code now}; returns how many were reclaimed. */
	@Transactional
	public int reclaim(Instant now)
	{
		List<ActionTask> overdue = tasks.listRunningPastDeadline(now);
		for (ActionTask task : overdue)
		{
			task.status = ActionRun.Status.FAILURE;
			task.finishedAt = now;
			CiRunner runner = task.runner;
			if (runner != null)
			{
				if (runner.ephemeral)
				{
					// an ephemeral runner is one-shot even when it dies mid-task — retire it, don't just flag it
					task.runner = null;
					runners.delete(runner);
				}
				else
				{
					runner.status = CiRunner.Status.OFFLINE;
				}
			}
			progress.rollUpRun(task.run);
		}
		return overdue.size();
	}

}
