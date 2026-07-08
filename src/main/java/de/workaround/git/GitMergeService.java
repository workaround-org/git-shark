package de.workaround.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.merge.ThreeWayMerger;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.revwalk.filter.RevFilter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.EmptyTreeIterator;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Diffing and merging of branches inside a bare repository, used by merge requests. Everything runs
 * in-core (no working tree), so it works against the same bare repos the transports serve. Nothing is
 * duplicated into the database — the diff is always computed live from git.
 */
@ApplicationScoped
public class GitMergeService
{
	/** A single line of a unified diff, tagged so the UI can colour it without re-parsing the patch. */
	public record DiffLine(String type, String text)
	{
	}

	/** One changed file: its path, the {@link DiffEntry.ChangeType} name, the diff lines and add/del counts. */
	public record FileDiff(String path, String changeType, List<DiffLine> lines, int additions, int deletions)
	{
	}

	/** The full set of changes the source branch would bring into the target (merge-base..source). */
	public record DiffView(List<FileDiff> files, int additions, int deletions)
	{
	}

	public enum MergeResult
	{
		/** The source branch was merged (or fast-forwarded) into the target and the target ref advanced. */
		MERGED,
		/** The three-way merge produced conflicts; nothing was written. */
		CONFLICT,
		/** The source branch is already contained in the target; there is nothing to merge. */
		UP_TO_DATE,
		/** One of the branches does not exist. */
		MISSING_BRANCH
	}

	public boolean branchExists(Path barePath, String branch)
	{
		try (Repository repo = open(barePath))
		{
			return repo.resolve(Constants.R_HEADS + branch) != null;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Computes the changes the source branch introduces on top of the target, i.e. the diff from the merge base
	 * of the two branches to the tip of the source (three-dot semantics). Returns empty if either branch is missing.
	 */
	public Optional<DiffView> diff(Path barePath, String targetBranch, String sourceBranch)
	{
		try (Repository repo = open(barePath); RevWalk walk = new RevWalk(repo))
		{
			ObjectId targetId = repo.resolve(Constants.R_HEADS + targetBranch);
			ObjectId sourceId = repo.resolve(Constants.R_HEADS + sourceBranch);
			if (targetId == null || sourceId == null)
			{
				return Optional.empty();
			}
			RevCommit source = walk.parseCommit(sourceId);
			RevCommit base = mergeBase(repo, targetId, sourceId);

			List<FileDiff> files = new ArrayList<>();
			int totalAdd = 0;
			int totalDel = 0;
			try (ObjectReader reader = repo.newObjectReader())
			{
				AbstractTreeIterator oldTree = base == null ? new EmptyTreeIterator() : tree(reader, base);
				AbstractTreeIterator newTree = tree(reader, source);
				List<DiffEntry> entries;
				try (DiffFormatter scanner = new DiffFormatter(DisabledOutputStream.INSTANCE))
				{
					scanner.setRepository(repo);
					entries = scanner.scan(oldTree, newTree);
				}
				for (DiffEntry entry : entries)
				{
					FileDiff file = formatEntry(repo, entry);
					files.add(file);
					totalAdd += file.additions();
					totalDel += file.deletions();
				}
			}
			return Optional.of(new DiffView(files, totalAdd, totalDel));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Merges the source branch into the target branch and advances the target ref. Fast-forwards when possible,
	 * otherwise records a two-parent merge commit authored by the given identity. Runs entirely in-core, so no
	 * working tree is required. Idempotent-ish: an already-merged source reports {@link MergeResult#UP_TO_DATE}.
	 */
	public MergeResult merge(Path barePath, String targetBranch, String sourceBranch, String committerName,
		String committerEmail, String message)
	{
		try (Repository repo = open(barePath); RevWalk walk = new RevWalk(repo))
		{
			ObjectId targetId = repo.resolve(Constants.R_HEADS + targetBranch);
			ObjectId sourceId = repo.resolve(Constants.R_HEADS + sourceBranch);
			if (targetId == null || sourceId == null)
			{
				return MergeResult.MISSING_BRANCH;
			}
			RevCommit target = walk.parseCommit(targetId);
			RevCommit source = walk.parseCommit(sourceId);

			if (walk.isMergedInto(source, target))
			{
				return MergeResult.UP_TO_DATE;
			}
			if (walk.isMergedInto(target, source))
			{
				updateRef(repo, targetBranch, targetId, sourceId, "fast-forward " + sourceBranch);
				return MergeResult.MERGED;
			}

			ThreeWayMerger merger = MergeStrategy.RECURSIVE.newMerger(repo, true);
			if (!merger.merge(target, source))
			{
				return MergeResult.CONFLICT;
			}
			ObjectId mergedTree = merger.getResultTreeId();
			ObjectId mergeCommit;
			try (ObjectInserter inserter = repo.newObjectInserter())
			{
				CommitBuilder builder = new CommitBuilder();
				builder.setTreeId(mergedTree);
				builder.setParentIds(target, source);
				PersonIdent ident = new PersonIdent(committerName, committerEmail);
				builder.setAuthor(ident);
				builder.setCommitter(ident);
				builder.setMessage(message);
				mergeCommit = inserter.insert(builder);
				inserter.flush();
			}
			updateRef(repo, targetBranch, targetId, mergeCommit, message);
			return MergeResult.MERGED;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static FileDiff formatEntry(Repository repo, DiffEntry entry) throws IOException
	{
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (DiffFormatter formatter = new DiffFormatter(out))
		{
			formatter.setRepository(repo);
			formatter.format(entry);
		}
		String patch = out.toString(StandardCharsets.UTF_8);
		List<DiffLine> lines = new ArrayList<>();
		int additions = 0;
		int deletions = 0;
		String[] raw = patch.split("\n", -1);
		for (int i = 0; i < raw.length; i++)
		{
			String line = raw[i];
			// a trailing empty element from the final newline is not a real line
			if (i == raw.length - 1 && line.isEmpty())
			{
				break;
			}
			String type;
			if (line.startsWith("@@"))
			{
				type = "hunk";
			}
			else if (line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ")
				|| line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file")
				|| line.startsWith("old mode") || line.startsWith("new mode") || line.startsWith("similarity ")
				|| line.startsWith("rename ") || line.startsWith("\\"))
			{
				type = "meta";
			}
			else if (line.startsWith("+"))
			{
				type = "add";
				additions++;
			}
			else if (line.startsWith("-"))
			{
				type = "del";
				deletions++;
			}
			else
			{
				type = "context";
			}
			lines.add(new DiffLine(type, line));
		}
		String path = entry.getChangeType() == DiffEntry.ChangeType.DELETE ? entry.getOldPath() : entry.getNewPath();
		return new FileDiff(path, entry.getChangeType().name(), lines, additions, deletions);
	}

	private static RevCommit mergeBase(Repository repo, ObjectId a, ObjectId b) throws IOException
	{
		try (RevWalk walk = new RevWalk(repo))
		{
			walk.setRevFilter(RevFilter.MERGE_BASE);
			walk.markStart(walk.parseCommit(a));
			walk.markStart(walk.parseCommit(b));
			RevCommit base = walk.next();
			return base == null ? null : walk.parseCommit(base);
		}
	}

	private static AbstractTreeIterator tree(ObjectReader reader, RevCommit commit) throws IOException
	{
		CanonicalTreeParser parser = new CanonicalTreeParser();
		parser.reset(reader, commit.getTree());
		return parser;
	}

	private static void updateRef(Repository repo, String branch, ObjectId oldId, ObjectId newId, String logMessage)
		throws IOException
	{
		RefUpdate update = repo.updateRef(Constants.R_HEADS + branch);
		update.setNewObjectId(newId);
		update.setExpectedOldObjectId(oldId);
		update.setRefLogMessage(logMessage, false);
		RefUpdate.Result result = update.update();
		if (result != RefUpdate.Result.FAST_FORWARD && result != RefUpdate.Result.FORCED
			&& result != RefUpdate.Result.NEW && result != RefUpdate.Result.NO_CHANGE)
		{
			throw new IllegalStateException("Failed to advance " + branch + ": " + result);
		}
	}

	private static Repository open(Path barePath) throws IOException
	{
		return new FileRepositoryBuilder().setGitDir(barePath.toFile()).setMustExist(true).build();
	}

}
