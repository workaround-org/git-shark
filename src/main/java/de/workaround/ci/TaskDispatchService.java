package de.workaround.ci;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import de.workaround.mirror.SecretCrypto;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionSecret;
import de.workaround.model.ActionTask;
import de.workaround.model.ActionVariable;
import de.workaround.model.CiRunner;
import de.workaround.model.Repository;
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
	ActionSecret.Repo secrets;

	@Inject
	ActionVariable.Repo variables;

	@Inject
	SecretCrypto crypto;

	@Inject
	EntityManager em;

	public record Fetched(Optional<ActionTask> task, long tasksVersion, Map<String, String> secrets,
		Map<String, String> vars, Map<String, NeedInfo> needs)
	{
	}

	/** A needed job's result and outputs, for the runner's {@code needs} context. */
	public record NeedInfo(ActionRun.Status result, Map<String, String> outputs)
	{
	}

	/**
	 * Authenticate the runner and claim the oldest label-compatible PENDING task, if any, delivering
	 * the owning repository's variables and (decrypted) secrets alongside it.
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
		Map<String, String> secretMap = next.map(task -> secretsFor(task.run.repository)).orElse(Map.of());
		Map<String, String> varMap = next.map(task -> variablesFor(task.run.repository)).orElse(Map.of());
		Map<String, NeedInfo> needsMap = next.map(this::needsResults).orElse(Map.of());
		return new Fetched(next, tasks.maxSeq(), secretMap, varMap, needsMap);
	}

	/** Decrypted repository secrets by name; a secret that cannot be decrypted is dropped, never leaked as ciphertext. */
	private Map<String, String> secretsFor(Repository repository)
	{
		Map<String, String> result = new HashMap<>();
		if (!crypto.available())
		{
			return result;
		}
		for (ActionSecret secret : secrets.findByRepository(repository))
		{
			try
			{
				result.put(secret.name, crypto.decrypt(secret.valueEncrypted));
			}
			catch (RuntimeException undecryptable)
			{
				// key rotated or value corrupt: omit rather than hand the runner unusable/ciphertext data
			}
		}
		return result;
	}

	private Map<String, String> variablesFor(Repository repository)
	{
		Map<String, String> result = new HashMap<>();
		for (ActionVariable variable : variables.findByRepository(repository))
		{
			result.put(variable.name, variable.value);
		}
		return result;
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
			ActionTask candidateTask = tasks.findById(id);
			if (!scopeAllows(runner, candidateTask) || !needsSatisfied(candidateTask))
			{
				continue;
			}
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

	/**
	 * Whether the runner's scope permits this task: a repo-scoped runner only its repository's tasks; an
	 * org-scoped runner any of that organisation's repositories'; an unscoped (instance) runner any task.
	 */
	private static boolean scopeAllows(CiRunner runner, ActionTask task)
	{
		Repository repo = task.run.repository;
		if (runner.repository != null)
		{
			return runner.repository.id.equals(repo.id);
		}
		if (runner.organisation != null)
		{
			return repo.ownerOrg != null && runner.organisation.id.equals(repo.ownerOrg.id);
		}
		return true;
	}

	/** A task is dispatchable only once every job it needs (in the same run) has succeeded. */
	private boolean needsSatisfied(ActionTask task)
	{
		Set<String> needed = splitLabels(task.needs);
		if (needed.isEmpty())
		{
			return true;
		}
		Map<String, List<ActionTask>> byJob = cellsByJob(task.run);
		return needed.stream().allMatch(jobId ->
		{
			List<ActionTask> cells = byJob.get(jobId);
			return cells != null && !cells.isEmpty()
				&& cells.stream().allMatch(cell -> cell.status == ActionRun.Status.SUCCESS);
		});
	}

	/** Tasks of a run grouped by job id; a matrix job has several cells under one id. */
	private Map<String, List<ActionTask>> cellsByJob(ActionRun run)
	{
		Map<String, List<ActionTask>> byJob = new HashMap<>();
		for (ActionTask sibling : tasks.findByRun(run))
		{
			byJob.computeIfAbsent(sibling.jobId, k -> new ArrayList<>()).add(sibling);
		}
		return byJob;
	}

	/** The aggregate result and merged outputs of the jobs a task needs, for its {@code needs} context. */
	private Map<String, NeedInfo> needsResults(ActionTask task)
	{
		Set<String> needed = splitLabels(task.needs);
		if (needed.isEmpty())
		{
			return Map.of();
		}
		Map<String, List<ActionTask>> byJob = cellsByJob(task.run);
		Map<String, NeedInfo> results = new HashMap<>();
		for (String jobId : needed)
		{
			List<ActionTask> cells = byJob.get(jobId);
			if (cells != null && !cells.isEmpty())
			{
				Map<String, String> merged = new HashMap<>();
				cells.forEach(cell -> merged.putAll(ActionOutputs.parse(cell.outputs)));
				results.put(jobId, new NeedInfo(aggregate(cells), merged));
			}
		}
		return results;
	}

	/** Worst-of over a job's cells: RUNNING if any unfinished, else FAILURE > CANCELLED > SUCCESS. */
	private static ActionRun.Status aggregate(List<ActionTask> cells)
	{
		if (cells.stream().anyMatch(c -> !c.status.isTerminal()))
		{
			return ActionRun.Status.RUNNING;
		}
		if (cells.stream().anyMatch(c -> c.status == ActionRun.Status.FAILURE))
		{
			return ActionRun.Status.FAILURE;
		}
		if (cells.stream().anyMatch(c -> c.status == ActionRun.Status.CANCELLED))
		{
			return ActionRun.Status.CANCELLED;
		}
		return ActionRun.Status.SUCCESS;
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
