package de.workaround.ci;

import java.util.List;
import java.util.UUID;

import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Persists a workflow run and its jobs in a single transaction (issue #2, phase 1). Split out from
 * {@link WorkflowIngestService} so the transaction boundary is a proper CDI-proxied call: the ingest
 * orchestration (git reads, trigger evaluation, error-swallowing) stays outside any transaction, and
 * each run is committed atomically here. The per-repository run {@link ActionRun#number} is allocated
 * as {@code max(number) + 1} inside the transaction, mirroring {@link de.workaround.model.Issue}.
 */
@ApplicationScoped
public class WorkflowRunFactory
{
	@Inject
	Repository.Repo repositories;

	@Inject
	User.Repo users;

	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Transactional
	public ActionRun create(Repository repository, UUID pusherUserId, String ref, String commitSha,
		String workflowName, String workflowFile, List<JobSpec> jobs, String payload)
	{
		Repository repo = repositories.findById(repository.id);

		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = runs.maxNumber(repo) + 1;
		run.workflowName = workflowName;
		run.workflowFile = workflowFile;
		run.event = "push";
		run.ref = ref;
		run.commitSha = commitSha;
		run.triggeredBy = pusherUserId == null ? null : users.findById(pusherUserId);
		run.persist();

		for (JobSpec job : jobs)
		{
			ActionTask task = new ActionTask();
			task.run = run;
			task.name = job.name();
			task.runsOn = job.runsOn();
			task.payload = payload;
			task.persist();
		}
		return run;
	}

	/** A job discovered in a workflow: its id and comma-joined {@code runs-on} labels. */
	public record JobSpec(String name, String runsOn)
	{
	}

}
