package de.workaround.git;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.ReceiveCommand;

import de.workaround.model.Repository;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Closes issues referenced by pushed commit messages, GitHub-style: a message containing a closing
 * keyword and an issue number (e.g. {@code fixes #12}, {@code closes #3}) moves that issue to DONE.
 * Invoked from the transports' post-receive hooks on a Git worker thread with no CDI request context,
 * so it activates one (like {@code FederationPushService}). Never throws into the Git path.
 */
@ApplicationScoped
public class IssueCommitCloser
{
	private static final Logger LOG = Logger.getLogger(IssueCommitCloser.class);

	private static final int MAX_COMMITS = 100;

	// close/closes/closed, fix/fixes/fixed, resolve/resolves/resolved — followed by #<number>
	private static final Pattern CLOSING = Pattern.compile(
		"(?i)\\b(?:close[sd]?|fix(?:e[sd])?|resolve[sd]?)\\s+#(\\d+)");

	@Inject
	GitRepositoryService repositories;

	@Inject
	IssueService issues;

	/** Entry point from the transports' post-receive hooks. */
	public void onPush(String ownerName, String repoName, UUID pusherUserId,
		org.eclipse.jgit.lib.Repository db, Collection<ReceiveCommand> commands)
	{
		var requestContext = Arc.container().requestContext();
		boolean activated = !requestContext.isActive();
		if (activated)
		{
			requestContext.activate();
		}
		try
		{
			close(ownerName, repoName, db, commands);
		}
		catch (RuntimeException e)
		{
			LOG.warnf(e, "Failed to close issues from pushed commits for %s/%s", ownerName, repoName);
		}
		finally
		{
			if (activated)
			{
				requestContext.terminate();
			}
		}
	}

	private void close(String ownerName, String repoName, org.eclipse.jgit.lib.Repository db,
		Collection<ReceiveCommand> commands)
	{
		Repository repo = repositories.find(ownerName, repoName).orElse(null);
		if (repo == null)
		{
			return;
		}
		Set<Integer> referenced = new LinkedHashSet<>();
		for (ReceiveCommand command : commands)
		{
			if (command.getResult() != ReceiveCommand.Result.OK
				|| !command.getRefName().startsWith("refs/heads/")
				|| command.getType() == ReceiveCommand.Type.DELETE)
			{
				continue;
			}
			referenced.addAll(referencedIssues(db, command.getOldId(), command.getNewId()));
		}
		for (int number : referenced)
		{
			issues.markDoneByNumber(repo, number);
		}
	}

	private static Set<Integer> referencedIssues(org.eclipse.jgit.lib.Repository db, ObjectId oldId, ObjectId newId)
	{
		Set<Integer> numbers = new LinkedHashSet<>();
		try (RevWalk walk = new RevWalk(db))
		{
			walk.markStart(walk.parseCommit(newId));
			if (oldId != null && !oldId.equals(ObjectId.zeroId()))
			{
				walk.markUninteresting(walk.parseCommit(oldId));
			}
			int count = 0;
			for (RevCommit commit : walk)
			{
				numbers.addAll(parseClosedIssues(commit.getFullMessage()));
				if (++count >= MAX_COMMITS)
				{
					break;
				}
			}
		}
		catch (Exception e)
		{
			// best-effort: enumerating new commits failed, so nothing is closed from this push
			LOG.debugf(e, "Could not enumerate pushed commits between %s and %s", oldId, newId);
		}
		return numbers;
	}

	/** Extracts the issue numbers referenced with a closing keyword in a commit message. */
	static Set<Integer> parseClosedIssues(String message)
	{
		Set<Integer> numbers = new LinkedHashSet<>();
		if (message == null)
		{
			return numbers;
		}
		Matcher matcher = CLOSING.matcher(message);
		while (matcher.find())
		{
			try
			{
				numbers.add(Integer.parseInt(matcher.group(1)));
			}
			catch (NumberFormatException overflow)
			{
				// a number larger than int can't match any issue; ignore it
			}
		}
		return numbers;
	}

}
