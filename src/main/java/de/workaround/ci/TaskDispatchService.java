package de.workaround.ci;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.CiRunner;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

/**
 * Hands PENDING tasks to runners over FetchTask (issue #2). Claiming a task flips it (and its run) to
 * RUNNING, records the claiming runner and a {@link #taskTimeout}-based {@link ActionTask#deadline}
 * for later zombie reclaim, and marks the runner ACTIVE — all in one transaction so a task is never
 * handed to two runners: the chosen row is locked {@code FOR UPDATE SKIP LOCKED} and re-checked for
 * PENDING status, so concurrent fetchers pick distinct rows (or none). A runner only claims a task
 * whose {@code runs-on} labels it advertises (label matching).
 *
 * <p>Phase-1 scope: no long-poll (returns immediately, empty when the queue is drained) and a coarse
 * {@code tasks_version} = highest task id issued (bumps on task creation, not on state change).
 */
@ApplicationScoped
public class TaskDispatchService
{
	/** How many oldest PENDING tasks to scan for a label-compatible one before giving up this fetch. */
	private static final int MAX_CANDIDATES = 100;

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

		Optional<ActionTask> next = claimOldestCompatible(runner).map(id ->
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
	 * Find and lock the oldest PENDING task the runner's labels satisfy. Candidates are read oldest-first
	 * (unlocked); the first label-compatible one is then locked by id with {@code FOR UPDATE SKIP LOCKED}
	 * and re-checked for PENDING status in the same statement, so a task is never handed to two runners:
	 * if another fetcher already claimed or locked it the lock select returns nothing and we move on.
	 * Selecting only the id keeps the {@code FOR UPDATE} off the nullable {@code runner} join.
	 */
	private Optional<UUID> claimOldestCompatible(CiRunner runner)
	{
		Set<String> runnerLabels = splitLabels(runner.labels);
		@SuppressWarnings("unchecked")
		List<Object[]> candidates = em.createNativeQuery(
			"select id, runs_on from action_task where status = 'PENDING' order by created_at asc limit " + MAX_CANDIDATES)
			.getResultList();
		for (Object[] candidate : candidates)
		{
			if (!labelsSatisfied((String) candidate[1], runnerLabels))
			{
				continue;
			}
			UUID id = (UUID) candidate[0];
			@SuppressWarnings("unchecked")
			List<UUID> locked = em.createNativeQuery(
				"select id from action_task where id = :id and status = 'PENDING' for update skip locked")
				.setParameter("id", id)
				.getResultList();
			if (!locked.isEmpty())
			{
				return Optional.of(id);
			}
		}
		return Optional.empty();
	}

	/** Whether the runner advertises every label the task's {@code runs-on} requires (empty = any). */
	private static boolean labelsSatisfied(String runsOn, Set<String> runnerLabels)
	{
		return splitLabels(runsOn).stream().allMatch(runnerLabels::contains);
	}

	private static Set<String> splitLabels(String csv)
	{
		if (csv == null || csv.isBlank())
		{
			return Set.of();
		}
		return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
			.collect(Collectors.toSet());
	}

}
