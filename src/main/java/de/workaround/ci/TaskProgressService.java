package de.workaround.ci;

import java.time.Instant;
import java.util.List;

import com.google.protobuf.Timestamp;

import de.workaround.ci.proto.runner.v1.LogRow;
import de.workaround.ci.proto.runner.v1.Result;
import de.workaround.model.ActionLog;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.CiRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Applies the progress a runner reports for its claimed task (issue #2, phase 1): UpdateTask records
 * the task result and rolls the owning run's status up from all its tasks; UpdateLog appends log rows
 * with resume-safe, contiguous-append semantics. Both authenticate the runner and reject a task that
 * is not assigned to it.
 */
@ApplicationScoped
public class TaskProgressService
{
	@Inject
	RunnerRegistrationService runnerService;

	@Inject
	ActionTask.Repo tasks;

	@Inject
	ActionLog.Repo logs;

	/**
	 * Record the result the runner reports for a task and roll the owning run's status up.
	 *
	 * @throws RunnerAuthenticationException if the credentials are bad or the task is not this runner's
	 * @throws TaskNotFoundException if no task has the given surrogate id
	 */
	@Transactional
	public ActionTask updateTask(String uuid, String token, long taskSeq, Result result, Instant stoppedAt)
	{
		CiRunner runner = authenticate(uuid, token);
		ActionTask task = ownedTask(taskSeq, runner);

		if (task.status.isTerminal())
		{
			// Already settled (e.g. reclaimed as a zombie); a late runner update must not resurrect it.
			return task;
		}
		ActionRun.Status status = map(result);
		task.status = status;
		if (status.isTerminal())
		{
			task.finishedAt = stoppedAt != null ? stoppedAt : Instant.now();
			runner.status = CiRunner.Status.IDLE;
		}
		rollUpRun(task.run);
		return task;
	}

	/**
	 * Append log rows a runner streams for a task and return the durable row count (the {@code
	 * ack_index}). Rows already stored (index below the current length) are ignored; a gap above the
	 * current length stops the append so the runner resends contiguously.
	 */
	@Transactional
	public long appendLog(String uuid, String token, long taskSeq, long index, List<LogRow> rows)
	{
		CiRunner runner = authenticate(uuid, token);
		ActionTask task = ownedTask(taskSeq, runner);

		int length = task.logLength;
		for (int i = 0; i < rows.size(); i++)
		{
			long line = index + i;
			if (line < length)
			{
				continue; // already stored (runner resend)
			}
			if (line > length)
			{
				break; // gap: refuse to store non-contiguously, runner will resend from `length`
			}
			LogRow row = rows.get(i);
			ActionLog log = new ActionLog();
			log.task = task;
			log.lineIndex = (int) line;
			log.content = row.getContent();
			log.timestamp = toInstant(row.getTime());
			log.persist();
			length++;
		}
		task.logLength = length;
		return length;
	}

	private CiRunner authenticate(String uuid, String token)
	{
		CiRunner runner = runnerService.authenticate(uuid, token);
		runner.lastSeen = Instant.now();
		return runner;
	}

	private ActionTask ownedTask(long taskSeq, CiRunner runner)
	{
		ActionTask task = tasks.findBySeq(taskSeq)
			.orElseThrow(() -> new TaskNotFoundException("No task with id " + taskSeq));
		if (task.runner == null || !task.runner.uuid.equals(runner.uuid))
		{
			throw new RunnerAuthenticationException("Task " + taskSeq + " is not assigned to this runner");
		}
		return task;
	}

	/** A single-job run mirrors its task; a multi-job run is RUNNING until all tasks finish, then the worst outcome. */
	public void rollUpRun(ActionRun run)
	{
		List<ActionTask> all = tasks.findByRun(run);
		boolean anyRunning = all.stream().anyMatch(t -> !t.status.isTerminal());
		if (anyRunning)
		{
			run.status = ActionRun.Status.RUNNING;
			return;
		}
		boolean anyFailure = all.stream().anyMatch(t -> t.status == ActionRun.Status.FAILURE);
		boolean anyCancelled = all.stream().anyMatch(t -> t.status == ActionRun.Status.CANCELLED);
		run.status = anyFailure ? ActionRun.Status.FAILURE
			: anyCancelled ? ActionRun.Status.CANCELLED
			: ActionRun.Status.SUCCESS;
		run.finishedAt = Instant.now();
	}

	private static ActionRun.Status map(Result result)
	{
		return switch (result)
		{
			case RESULT_SUCCESS, RESULT_SKIPPED -> ActionRun.Status.SUCCESS;
			case RESULT_FAILURE -> ActionRun.Status.FAILURE;
			case RESULT_CANCELLED -> ActionRun.Status.CANCELLED;
			// RESULT_UNSPECIFIED (and any unknown) is a mid-run heartbeat: still running.
			default -> ActionRun.Status.RUNNING;
		};
	}

	private static Instant toInstant(Timestamp timestamp)
	{
		if (timestamp == null || (timestamp.getSeconds() == 0 && timestamp.getNanos() == 0))
		{
			return Instant.now();
		}
		return Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
	}

}
