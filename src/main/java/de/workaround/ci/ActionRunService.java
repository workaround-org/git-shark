package de.workaround.ci;

import java.time.Instant;

import de.workaround.model.ActionLog;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Run-level controls: cancel and re-run (issue #2, phase 2). Callers must have already gated on
 * repository write access.
 */
@ApplicationScoped
public class ActionRunService
{
	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Inject
	ActionLog.Repo logs;

	/**
	 * Cancel a run: settle the run and every task that has not already finished. A task still RUNNING
	 * on a runner keeps its assignment but is marked CANCELLED; the runner is told to stop the next
	 * time it calls UpdateTask (see {@code ConnectRunnerResource}).
	 */
	@Transactional
	public void cancel(ActionRun detached)
	{
		ActionRun run = runs.findById(detached.id);
		if (run.status.isTerminal())
		{
			// already finished (naturally or a double click on a stale page) — don't rewrite history
			return;
		}
		Instant now = Instant.now();
		for (ActionTask task : tasks.findByRun(run))
		{
			if (!task.status.isTerminal())
			{
				task.status = ActionRun.Status.CANCELLED;
				task.finishedAt = now;
			}
		}
		run.status = ActionRun.Status.CANCELLED;
		run.finishedAt = now;
	}

	/**
	 * Re-run a finished run: reset every task to PENDING (clearing runner assignment, timing, logs and
	 * outputs) and the run to PENDING, so runners pick the work up fresh. No-op if the run is not
	 * terminal.
	 */
	@Transactional
	public void rerun(ActionRun detached)
	{
		ActionRun run = runs.findById(detached.id);
		if (!run.status.isTerminal())
		{
			return;
		}
		for (ActionTask task : tasks.findByRun(run))
		{
			logs.deleteByTask(task);
			task.status = ActionRun.Status.PENDING;
			task.runner = null;
			task.startedAt = null;
			task.finishedAt = null;
			task.deadline = null;
			task.logLength = 0;
			task.outputs = "{}";
		}
		run.status = ActionRun.Status.PENDING;
		run.startedAt = null;
		run.finishedAt = null;
	}
}
