package de.workaround.ci;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.CiRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Hands PENDING tasks to runners over FetchTask (issue #2, phase 1). Claiming a task flips it (and its
 * run) to RUNNING, records the claiming runner and a {@link #taskTimeout}-based {@link
 * ActionTask#deadline} for later zombie reclaim, and marks the runner ACTIVE — all in one transaction
 * so a task is never handed to two runners: the candidate row is selected {@code FOR UPDATE SKIP
 * LOCKED}, so concurrent fetchers pick distinct rows (or none) rather than racing on the same one.
 *
 * <p>Phase-1 scope: no long-poll (returns immediately, empty when the queue is drained) and a coarse
 * {@code tasks_version} = highest task id issued (bumps on task creation, not on state change).
 */
@ApplicationScoped
public class TaskDispatchService
{
	/** How long a claimed task may run before {@link ZombieReclaimService} may reclaim it. */
	@ConfigProperty(name = "gitshark.ci.task-timeout", defaultValue = "1h")
	Duration taskTimeout;

	@Inject
	RunnerRegistrationService runnerService;

	@Inject
	ActionTask.Repo tasks;

	@Inject
	EntityManager em;

	public record Fetched(Optional<ActionTask> task, long tasksVersion)
	{
	}

	/**
	 * Authenticate the runner and claim the oldest PENDING task, if any.
	 *
	 * @throws RunnerAuthenticationException if the uuid/token pair is unknown
	 */
	@Transactional
	public Fetched fetch(String uuid, String token)
	{
		CiRunner runner = runnerService.authenticate(uuid, token);
		runner.lastSeen = Instant.now();

		Optional<ActionTask> next = lockOldestPending().map(id ->
		{
			ActionTask task = tasks.findById(id);
			claim(task, runner);
			return task;
		});
		return new Fetched(next, tasks.maxSeq());
	}

	private void claim(ActionTask task, CiRunner runner)
	{
		Instant now = Instant.now();
		task.runner = runner;
		task.status = ActionRun.Status.RUNNING;
		task.startedAt = now;
		task.deadline = now.plus(taskTimeout);

		runner.status = CiRunner.Status.ACTIVE;

		ActionRun run = task.run;
		if (run.status == ActionRun.Status.PENDING)
		{
			run.status = ActionRun.Status.RUNNING;
			run.startedAt = now;
		}
	}

	/**
	 * Lock the oldest PENDING task's id with {@code FOR UPDATE SKIP LOCKED} so a concurrent fetcher in
	 * another transaction cannot claim the same row. Selecting only the id keeps the {@code FOR UPDATE}
	 * off the nullable {@code runner} association (Postgres rejects it on the nullable side of a join).
	 */
	private Optional<UUID> lockOldestPending()
	{
		@SuppressWarnings("unchecked")
		List<UUID> ids = em.createNativeQuery(
			"select id from action_task where status = 'PENDING' order by created_at asc limit 1 for update skip locked")
			.getResultList();
		return ids.isEmpty() ? Optional.empty() : Optional.of(ids.get(0));
	}

}
