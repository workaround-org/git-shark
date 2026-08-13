package de.workaround.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TagBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Reads and writes tags of a bare repository. Tag creation runs in-core against the bare repo (no working
 * tree), the same way {@link GitMergeService} writes merge commits, so a release can cut its tag from a branch
 * without anyone having to push one first.
 */
@ApplicationScoped
public class GitTagService
{
	/** Resolves any ref (tag, branch or object id) to the commit it points at, peeling annotated tags. */
	public Optional<String> resolveCommit(Path barePath, String ref)
	{
		if (ref == null || ref.isBlank())
		{
			return Optional.empty();
		}
		try (Repository repo = open(barePath); RevWalk walk = new RevWalk(repo))
		{
			ObjectId id = repo.resolve(ref + "^{commit}");
			return id == null ? Optional.empty() : Optional.of(walk.parseCommit(id).getName());
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public boolean exists(Path barePath, String tag)
	{
		try (Repository repo = open(barePath))
		{
			return repo.exactRef(Constants.R_TAGS + tag) != null;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Creates an annotated tag named {@code tag} at the commit {@code target} resolves to and returns that
	 * commit's id. Rejects an invalid or already existing tag name and an unresolvable target.
	 */
	public String createTag(Path barePath, String tag, String target, PersonIdent tagger, String message)
	{
		if (!Repository.isValidRefName(Constants.R_TAGS + tag))
		{
			throw new InvalidReleaseException("'" + tag + "' is not a valid tag name");
		}
		try (Repository repo = open(barePath); RevWalk walk = new RevWalk(repo))
		{
			if (repo.exactRef(Constants.R_TAGS + tag) != null)
			{
				throw new InvalidReleaseException("Tag '" + tag + "' already exists");
			}
			ObjectId targetId = repo.resolve(target + "^{commit}");
			if (targetId == null)
			{
				throw new InvalidReleaseException("'" + target + "' does not name a branch, tag or commit");
			}
			RevCommit commit = walk.parseCommit(targetId);
			ObjectId tagId;
			try (ObjectInserter inserter = repo.newObjectInserter())
			{
				TagBuilder builder = new TagBuilder();
				builder.setTag(tag);
				builder.setObjectId(commit);
				builder.setTagger(tagger);
				builder.setMessage(message);
				tagId = inserter.insert(builder);
				inserter.flush();
			}
			RefUpdate update = repo.updateRef(Constants.R_TAGS + tag);
			update.setNewObjectId(tagId);
			update.setExpectedOldObjectId(ObjectId.zeroId());
			update.setRefLogMessage("tagged " + tag, false);
			RefUpdate.Result result = update.update();
			if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED)
			{
				throw new IllegalStateException("Failed to create tag " + tag + ": " + result);
			}
			return commit.getName();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static Repository open(Path barePath) throws IOException
	{
		return new FileRepositoryBuilder().setGitDir(barePath.toFile()).setMustExist(true).build();
	}

}
