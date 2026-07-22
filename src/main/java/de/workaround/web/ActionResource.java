package de.workaround.web;

import java.util.List;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionLog;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.Repository;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriInfo;

/**
 * Read-only Actions UI for a repository (issue #2, phase 1): a run list and a run detail page showing
 * each job (task) and its streamed log rows. Mirrors {@link IssueResource}'s structure — resolve the
 * repository, gate on read access (404 to hide private repos), and render a {@code @CheckedTemplate}.
 */
@jakarta.ws.rs.Path("/repos/{owner}/{name}/actions")
@Produces(MediaType.TEXT_HTML)
public class ActionResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance runs(Repository repo, RepoNav nav, List<ActionRun> runs);

		static native TemplateInstance run(Repository repo, RepoNav nav, ActionRun run, List<TaskLogs> tasks);
	}

	/** A task paired with its log rows, for the run detail page. */
	public record TaskLogs(ActionTask task, List<ActionLog> logs)
	{
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService service;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	RepoNavService repoNav;

	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Inject
	ActionLog.Repo logs;

	@Context
	UriInfo uriInfo;

	@GET
	public TemplateInstance list(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		return Templates.runs(repo, repoNav.build(repo, uriInfo), runs.findByRepository(repo));
	}

	@GET
	@jakarta.ws.rs.Path("{number:\\d+}")
	public TemplateInstance detail(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("number") int number)
	{
		Repository repo = requireReadable(owner, name);
		ActionRun run = runs.findByRepositoryAndNumber(repo, number).orElseThrow(NotFoundException::new);
		List<TaskLogs> taskLogs = tasks.findByRun(run).stream()
			.map(task -> new TaskLogs(task, logs.findByTask(task)))
			.toList();
		return Templates.run(repo, repoNav.build(repo, uriInfo), run, taskLogs);
	}

	private Repository requireReadable(String owner, String name)
	{
		Repository repo = service.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(currentUser.get(), repo))
		{
			throw new NotFoundException();
		}
		return repo;
	}

}
