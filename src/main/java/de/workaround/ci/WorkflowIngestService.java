package de.workaround.ci;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.eclipse.jgit.errors.LargeObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionRun;
import de.workaround.model.Repository;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Detects workflow files pushed to a repository and materializes CI runs (issue #2). For every
 * branch or tag update, workflow files under {@code .forgejo/workflows/} and {@code .gitea/workflows/}
 * at the new commit are parsed; those whose {@code push} trigger matches the ref produce one {@link
 * de.workaround.model.ActionRun} with one {@link de.workaround.model.ActionTask} per job.
 *
 * <p>Supported today: {@code on: push} with {@code branches}/{@code tags}/{@code paths} (and their
 * {@code -ignore} variants) filters. Not yet: non-push events, {@code needs} and {@code matrix}.
 * Invoked from the transports' post-receive hooks on a Git worker thread with no CDI request context,
 * so it activates one and never throws into the Git path.
 */
@ApplicationScoped
public class WorkflowIngestService
{
	private static final Logger LOG = Logger.getLogger(WorkflowIngestService.class);

	private static final List<String> WORKFLOW_DIRS = List.of(".forgejo/workflows", ".gitea/workflows");

	private static final int MAX_WORKFLOW_BYTES = 512 * 1024;

	private static final int MAX_CHANGED_PATHS = 5000;

	private static final YAMLMapper YAML = new YAMLMapper();

	@Inject
	GitRepositoryService repositories;

	@Inject
	WorkflowRunFactory factory;

	@Inject
	ActionRunService runControl;

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
			RefTarget target = classify(command.getRefName());
			if (command.getResult() != ReceiveCommand.Result.OK
				|| command.getType() == ReceiveCommand.Type.DELETE
				|| target == null)
			{
				continue;
			}
			List<WorkflowFile> workflows = readWorkflows(db, command.getNewId());
			if (workflows.isEmpty())
			{
				continue;
			}
			List<String> changedPaths = changedPaths(db, command.getOldId(), command.getNewId());
			Set<UUID> createdRuns = new HashSet<>();
			for (WorkflowFile workflow : workflows)
			{
				JsonNode root = parse(workflow.content());
				if (root == null || !pushMatches(root, target, changedPaths))
				{
					continue;
				}
				List<WorkflowRunFactory.JobSpec> jobs = jobs(root);
				if (jobs.isEmpty())
				{
					continue;
				}
				ActionRun run = factory.create(repo, pusherUserId, command.getRefName(), command.getNewId().name(),
					workflowName(root, workflow.path()), workflow.path(), jobs);
				createdRuns.add(run.id);
			}
			if (!createdRuns.isEmpty())
			{
				// a new push to this branch supersedes its still-active earlier runs
				runControl.cancelSuperseded(repo, command.getRefName(), createdRuns);
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

	/**
	 * The repository-relative paths changed between the old and new commit of a push (added, modified
	 * or deleted). A brand-new ref (old = zero) is diffed against the empty tree. Capped at {@link
	 * #MAX_CHANGED_PATHS} so a huge push cannot exhaust memory; best-effort (empty on error).
	 */
	private static List<String> changedPaths(org.eclipse.jgit.lib.Repository db, ObjectId oldId, ObjectId newId)
	{
		List<String> paths = new ArrayList<>();
		try (RevWalk walk = new RevWalk(db); TreeWalk treeWalk = new TreeWalk(db))
		{
			if (oldId != null && !oldId.equals(ObjectId.zeroId()))
			{
				treeWalk.addTree(walk.parseCommit(oldId).getTree());
			}
			else
			{
				treeWalk.addTree(new EmptyTreeIterator());
			}
			treeWalk.addTree(walk.parseCommit(newId).getTree());
			treeWalk.setRecursive(true);
			treeWalk.setFilter(TreeFilter.ANY_DIFF);
			while (treeWalk.next() && paths.size() < MAX_CHANGED_PATHS)
			{
				paths.add(treeWalk.getPathString());
			}
		}
		catch (Exception e)
		{
			// best-effort: without a diff, path filters simply won't match (no run)
			LOG.debugf(e, "Could not diff %s..%s for path filters", oldId, newId);
		}
		return paths;
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

	private enum RefKind
	{
		BRANCH,
		TAG
	}

	private record RefTarget(RefKind kind, String name)
	{
	}

	private static RefTarget classify(String ref)
	{
		if (ref.startsWith("refs/heads/"))
		{
			return new RefTarget(RefKind.BRANCH, ref.substring("refs/heads/".length()));
		}
		if (ref.startsWith("refs/tags/"))
		{
			return new RefTarget(RefKind.TAG, ref.substring("refs/tags/".length()));
		}
		return null;
	}

	/**
	 * Whether the workflow's {@code on:} triggers a run for this ref. YAML 1.1 coerces the bare key
	 * {@code on} to boolean true, so SnakeYAML/Jackson may surface it under {@code "true"} — both are
	 * checked. A bare/list {@code on: push} triggers on any branch push (never tags); an
	 * {@code on: { push: {...} }} object honors {@code branches}/{@code branches-ignore} and
	 * {@code tags}/{@code tags-ignore} with GitHub-style globs, plus {@code paths}/{@code paths-ignore}
	 * against the changed files.
	 */
	static boolean pushMatches(JsonNode root, RefTarget target, List<String> changedPaths)
	{
		JsonNode on = root.has("on") ? root.get("on") : root.get("true");
		if (on == null)
		{
			return false;
		}
		if (on.isTextual())
		{
			return "push".equals(on.asText()) && target.kind() == RefKind.BRANCH;
		}
		if (on.isArray())
		{
			for (JsonNode event : on)
			{
				if (event.isTextual() && "push".equals(event.asText()))
				{
					return target.kind() == RefKind.BRANCH;
				}
			}
			return false;
		}
		if (!on.isObject())
		{
			return false;
		}
		JsonNode push = on.get("push");
		if (push == null)
		{
			return false;
		}
		if (!push.isObject())
		{
			// `push:` with no filter block triggers on any branch push (never tags)
			return target.kind() == RefKind.BRANCH;
		}
		return refMatchesFilters(push, target) && pathMatches(push, changedPaths);
	}

	private static boolean refMatchesFilters(JsonNode push, RefTarget target)
	{
		if (target.kind() == RefKind.BRANCH)
		{
			if (push.has("branches"))
			{
				return matchesAnyGlob(push.get("branches"), target.name());
			}
			if (push.has("branches-ignore"))
			{
				return !matchesAnyGlob(push.get("branches-ignore"), target.name());
			}
			// a tag-only filter block excludes branch pushes; anything else (e.g. paths only) allows them
			return !push.has("tags") && !push.has("tags-ignore");
		}
		if (push.has("tags"))
		{
			return matchesAnyGlob(push.get("tags"), target.name());
		}
		if (push.has("tags-ignore"))
		{
			return !matchesAnyGlob(push.get("tags-ignore"), target.name());
		}
		// tags must be opted into explicitly
		return false;
	}

	/**
	 * Evaluates {@code paths}/{@code paths-ignore} against the files changed by the push. {@code paths}
	 * runs when any changed file matches; {@code paths-ignore} runs unless every changed file is
	 * ignored. Neither key means no path constraint.
	 */
	private static boolean pathMatches(JsonNode push, List<String> changedPaths)
	{
		if (push.has("paths"))
		{
			JsonNode paths = push.get("paths");
			return changedPaths.stream().anyMatch(file -> matchesAnyGlob(paths, file));
		}
		if (push.has("paths-ignore"))
		{
			JsonNode ignore = push.get("paths-ignore");
			return !changedPaths.stream().allMatch(file -> matchesAnyGlob(ignore, file));
		}
		return true;
	}

	private static boolean matchesAnyGlob(JsonNode patterns, String name)
	{
		if (patterns == null)
		{
			return false;
		}
		if (patterns.isTextual())
		{
			return name.matches(globToRegex(patterns.asText()));
		}
		if (patterns.isArray())
		{
			for (JsonNode pattern : patterns)
			{
				if (pattern.isTextual() && name.matches(globToRegex(pattern.asText())))
				{
					return true;
				}
			}
		}
		return false;
	}

	/** GitHub ref-filter glob → regex: {@code **} spans {@code /}, {@code *} and {@code ?} do not. */
	static String globToRegex(String glob)
	{
		StringBuilder regex = new StringBuilder();
		for (int i = 0; i < glob.length(); i++)
		{
			char c = glob.charAt(i);
			switch (c)
			{
				case '*':
					if (i + 1 < glob.length() && glob.charAt(i + 1) == '*')
					{
						regex.append(".*");
						i++;
					}
					else
					{
						regex.append("[^/]*");
					}
					break;
				case '?':
					regex.append("[^/]");
					break;
				case '.', '(', ')', '+', '|', '^', '$', '{', '}', '[', ']', '\\':
					regex.append('\\').append(c);
					break;
				default:
					regex.append(c);
			}
		}
		return regex.toString();
	}

	private static List<WorkflowRunFactory.JobSpec> jobs(JsonNode root)
	{
		List<WorkflowRunFactory.JobSpec> specs = new ArrayList<>();
		JsonNode jobs = root.get("jobs");
		if (jobs != null && jobs.isObject())
		{
			for (Iterator<String> it = jobs.fieldNames(); it.hasNext();)
			{
				String jobId = it.next();
				JsonNode job = jobs.get(jobId);
				for (Map<String, String> cell : matrixCells(job))
				{
					String name = cell.isEmpty() ? jobId : jobId + " (" + String.join(", ", cell.values()) + ")";
					String payload = singleJobPayload(root, jobId, job, cell);
					specs.add(new WorkflowRunFactory.JobSpec(name, jobId, runsOn(job), needs(job), payload));
				}
			}
		}
		return specs;
	}

	/**
	 * The matrix combinations of a job: one empty map when the job has no {@code strategy.matrix},
	 * otherwise the cartesian product of its dimensions (insertion order preserved, {@code include}/
	 * {@code exclude} not yet supported). Each combination maps dimension name to its value.
	 */
	private static List<Map<String, String>> matrixCells(JsonNode job)
	{
		JsonNode matrix = job.path("strategy").path("matrix");
		if (!matrix.isObject())
		{
			return List.of(new LinkedHashMap<>());
		}
		List<Map<String, String>> cells = new ArrayList<>();
		cells.add(new LinkedHashMap<>());
		for (Iterator<String> it = matrix.fieldNames(); it.hasNext();)
		{
			String dim = it.next();
			if (dim.equals("include") || dim.equals("exclude"))
			{
				continue;
			}
			JsonNode values = matrix.get(dim);
			if (!values.isArray() || values.isEmpty())
			{
				continue;
			}
			List<Map<String, String>> expanded = new ArrayList<>();
			for (Map<String, String> base : cells)
			{
				for (JsonNode value : values)
				{
					Map<String, String> next = new LinkedHashMap<>(base);
					next.put(dim, value.asText());
					expanded.add(next);
				}
			}
			cells = expanded;
		}
		return cells;
	}

	/**
	 * A standalone single-job workflow for one task: the original {@code name}/{@code on} plus just
	 * this job, with its matrix (if any) reduced to the given cell so the runner resolves {@code
	 * matrix.*} to a single combination.
	 */
	private static String singleJobPayload(JsonNode root, String jobId, JsonNode job, Map<String, String> cell)
	{
		ObjectNode out = YAML.createObjectNode();
		if (root.has("name"))
		{
			out.set("name", root.get("name"));
		}
		JsonNode on = root.has("on") ? root.get("on") : root.get("true");
		if (on != null)
		{
			out.set("on", on);
		}
		ObjectNode jobCopy = job.deepCopy();
		if (!cell.isEmpty())
		{
			ObjectNode strategy = jobCopy.has("strategy") && jobCopy.get("strategy").isObject()
				? (ObjectNode) jobCopy.get("strategy")
				: jobCopy.putObject("strategy");
			ObjectNode matrix = strategy.putObject("matrix");
			cell.forEach((dim, value) -> matrix.putArray(dim).add(value));
		}
		out.putObject("jobs").set(jobId, jobCopy);
		try
		{
			return YAML.writeValueAsString(out);
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Could not build single-job payload for " + jobId, e);
		}
	}

	/** The job's {@code runs-on} as comma-joined labels; string or list, empty when absent. */
	private static String runsOn(JsonNode job)
	{
		return scalarOrList(job, "runs-on");
	}

	/** The job's {@code needs} as comma-joined job names; string or list, empty when absent. */
	private static String needs(JsonNode job)
	{
		return scalarOrList(job, "needs");
	}

	/** A workflow field that may be a single string or a list of strings, returned comma-joined. */
	private static String scalarOrList(JsonNode job, String field)
	{
		JsonNode node = job == null ? null : job.get(field);
		if (node == null)
		{
			return "";
		}
		if (node.isTextual())
		{
			return node.asText();
		}
		if (node.isArray())
		{
			List<String> values = new ArrayList<>();
			for (JsonNode value : node)
			{
				if (value.isTextual())
				{
					values.add(value.asText());
				}
			}
			return String.join(",", values);
		}
		return "";
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
