package de.workaround.ci;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Detects workflow files pushed to a repository and materializes CI runs (issue #2, phase 1). For
 * every branch update, workflow files under {@code .forgejo/workflows/} and {@code .gitea/workflows/}
 * at the new commit are parsed; those triggered by {@code push} produce one {@link
 * de.workaround.model.ActionRun} with one {@link de.workaround.model.ActionTask} per job.
 *
 * <p>Phase-1 scope: {@code on: push} only (branch/tag/path filters, other events, {@code needs} and
 * {@code matrix} are phase 2). Invoked from the transports' post-receive hooks on a Git worker thread
 * with no CDI request context, so it activates one and never throws into the Git path.
 */
@ApplicationScoped
public class WorkflowIngestService
{
	private static final Logger LOG = Logger.getLogger(WorkflowIngestService.class);

	private static final List<String> WORKFLOW_DIRS = List.of(".forgejo/workflows", ".gitea/workflows");

	private static final int MAX_WORKFLOW_BYTES = 512 * 1024;

	private static final YAMLMapper YAML = new YAMLMapper();

	@Inject
	GitRepositoryService repositories;

	@Inject
	WorkflowRunFactory factory;

	/** Entry point from the transports' post-receive hooks. */
	public void onPush(String ownerName, String repoName, UUID pusherUserId,
		org.eclipse.jgit.lib.Repository db, java.util.Collection<ReceiveCommand> commands)
	{
		var requestContext = Arc.container().requestContext();
		boolean activated = !requestContext.isActive();
		if (activated)
		{
			requestContext.activate();
		}
		try
		{
			ingest(ownerName, repoName, pusherUserId, db, commands);
		}
		catch (RuntimeException e)
		{
			LOG.warnf(e, "Failed to ingest workflows from pushed commits for %s/%s", ownerName, repoName);
		}
		finally
		{
			if (activated)
			{
				requestContext.terminate();
			}
		}
	}

	private void ingest(String ownerName, String repoName, UUID pusherUserId,
		org.eclipse.jgit.lib.Repository db, java.util.Collection<ReceiveCommand> commands)
	{
		Repository repo = repositories.find(ownerName, repoName).orElse(null);
		if (repo == null)
		{
			return;
		}
		for (ReceiveCommand command : commands)
		{
			if (command.getResult() != ReceiveCommand.Result.OK
				|| !command.getRefName().startsWith("refs/heads/")
				|| command.getType() == ReceiveCommand.Type.DELETE)
			{
				continue;
			}
			for (WorkflowFile workflow : readWorkflows(db, command.getNewId()))
			{
				JsonNode root = parse(workflow.content());
				if (root == null || !triggeredByPush(root))
				{
					continue;
				}
				List<String> jobs = jobNames(root);
				if (jobs.isEmpty())
				{
					continue;
				}
				factory.create(repo, pusherUserId, command.getRefName(), command.getNewId().name(),
					workflowName(root, workflow.path()), workflow.path(), jobs, workflow.content());
			}
		}
	}

	private static List<WorkflowFile> readWorkflows(org.eclipse.jgit.lib.Repository db, ObjectId commitId)
	{
		List<WorkflowFile> out = new ArrayList<>();
		try (RevWalk walk = new RevWalk(db))
		{
			RevCommit commit = walk.parseCommit(commitId);
			for (String dir : WORKFLOW_DIRS)
			{
				collect(db, commit, dir, out);
			}
		}
		catch (Exception e)
		{
			// best-effort: reading the pushed tree failed, so no runs are created from this push
			LOG.debugf(e, "Could not read workflow files at %s", commitId);
		}
		return out;
	}

	private static void collect(org.eclipse.jgit.lib.Repository db, RevCommit commit, String dir,
		List<WorkflowFile> out) throws Exception
	{
		try (TreeWalk walk = new TreeWalk(db))
		{
			walk.addTree(commit.getTree());
			walk.setRecursive(true);
			walk.setFilter(PathFilter.create(dir));
			while (walk.next())
			{
				String path = walk.getPathString();
				if (!path.endsWith(".yml") && !path.endsWith(".yaml"))
				{
					continue;
				}
				try
				{
					byte[] content = db.open(walk.getObjectId(0), Constants.OBJ_BLOB).getCachedBytes(MAX_WORKFLOW_BYTES);
					out.add(new WorkflowFile(path, new String(content, StandardCharsets.UTF_8)));
				}
				catch (LargeObjectException tooBig)
				{
					LOG.debugf("Skipping oversized workflow file %s", path);
				}
			}
		}
	}

	private static JsonNode parse(String yaml)
	{
		try
		{
			return YAML.readTree(yaml);
		}
		catch (Exception malformed)
		{
			LOG.debugf(malformed, "Skipping unparseable workflow file");
			return null;
		}
	}

	/**
	 * Reads the {@code on:} trigger. YAML 1.1 coerces the bare key {@code on} to boolean true, so
	 * SnakeYAML/Jackson may surface it under the field name {@code "true"} — both keys are checked.
	 */
	static boolean triggeredByPush(JsonNode root)
	{
		JsonNode on = root.has("on") ? root.get("on") : root.get("true");
		if (on == null)
		{
			return false;
		}
		if (on.isTextual())
		{
			return "push".equals(on.asText());
		}
		if (on.isArray())
		{
			for (JsonNode event : on)
			{
				if (event.isTextual() && "push".equals(event.asText()))
				{
					return true;
				}
			}
			return false;
		}
		return on.isObject() && on.has("push");
	}

	private static List<String> jobNames(JsonNode root)
	{
		List<String> names = new ArrayList<>();
		JsonNode jobs = root.get("jobs");
		if (jobs != null && jobs.isObject())
		{
			for (Iterator<String> it = jobs.fieldNames(); it.hasNext();)
			{
				names.add(it.next());
			}
		}
		return names;
	}

	private static String workflowName(JsonNode root, String path)
	{
		JsonNode name = root.get("name");
		if (name != null && name.isTextual() && !name.asText().isBlank())
		{
			return name.asText();
		}
		int slash = path.lastIndexOf('/');
		return slash >= 0 ? path.substring(slash + 1) : path;
	}

	private record WorkflowFile(String path, String content)
	{
	}

}
