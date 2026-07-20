package de.workaround.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilter;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Read-only views into bare repositories for the web UI. All data is read live from JGit;
 * nothing is duplicated into the database.
 */
@ApplicationScoped
public class GitBrowseService
{
	private static final int MAX_DISPLAY_BYTES = 1024 * 1024;

	public record TreeEntry(String name, String path, boolean directory)
	{
	}

	public record BlobView(byte[] content, boolean binary)
	{
	}

	public record CommitInfo(String id, String shortId, String message, String author, Instant date)
	{
	}

	public record CommitPage(List<CommitInfo> commits, boolean hasNext)
	{
	}

	public record BranchInfo(String name, boolean defaultBranch)
	{
	}

	public record Contributor(String name, String email, int commits)
	{
	}

	public boolean isEmpty(Path barePath)
	{
		try (Repository repo = open(barePath))
		{
			return repo.resolve(Constants.HEAD) == null;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public String defaultBranch(Path barePath)
	{
		try (Repository repo = open(barePath))
		{
			Ref head = repo.exactRef(Constants.HEAD);
			if (head != null && head.isSymbolic())
			{
				return Repository.shortenRefName(head.getTarget().getName());
			}
			return "main";
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public Optional<List<TreeEntry>> listTree(Path barePath, String ref, String path)
	{
		try (Repository repo = open(barePath); RevWalk revWalk = new RevWalk(repo))
		{
			RevCommit commit = resolveCommit(repo, revWalk, ref);
			if (commit == null)
			{
				return Optional.empty();
			}
			List<TreeEntry> entries = new ArrayList<>();
			try (TreeWalk walk = new TreeWalk(repo))
			{
				walk.addTree(commit.getTree());
				walk.setRecursive(false);
				if (!path.isEmpty())
				{
					walk.setFilter(PathFilter.create(path));
					boolean entered = false;
					while (walk.next())
					{
						if (walk.getPathString().equals(path))
						{
							if (!walk.isSubtree())
							{
								return Optional.empty();
							}
							walk.enterSubtree();
							entered = true;
							break;
						}
						if (walk.isSubtree())
						{
							walk.enterSubtree();
						}
					}
					if (!entered)
					{
						return Optional.empty();
					}
				}
				while (walk.next())
				{
					entries.add(new TreeEntry(walk.getNameString(), walk.getPathString(), walk.isSubtree()));
				}
			}
			entries.sort(Comparator.comparing(TreeEntry::directory).reversed().thenComparing(TreeEntry::name));
			return Optional.of(entries);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public Optional<BlobView> blob(Path barePath, String ref, String path)
	{
		try (Repository repo = open(barePath); RevWalk revWalk = new RevWalk(repo))
		{
			RevCommit commit = resolveCommit(repo, revWalk, ref);
			if (commit == null)
			{
				return Optional.empty();
			}
			try (TreeWalk walk = TreeWalk.forPath(repo, path, commit.getTree()))
			{
				if (walk == null || walk.isSubtree())
				{
					return Optional.empty();
				}
				byte[] bytes = repo.open(walk.getObjectId(0)).getCachedBytes(MAX_DISPLAY_BYTES);
				return Optional.of(new BlobView(bytes, RawText.isBinary(bytes, bytes.length, true)));
			}
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public Optional<CommitPage> commits(Path barePath, String ref, int page, int size)
	{
		try (Repository repo = open(barePath); RevWalk revWalk = new RevWalk(repo))
		{
			RevCommit start = resolveCommit(repo, revWalk, ref);
			if (start == null)
			{
				return Optional.empty();
			}
			revWalk.markStart(start);
			List<CommitInfo> commits = new ArrayList<>();
			boolean hasNext = false;
			int skip = page * size;
			int index = 0;
			for (RevCommit commit : revWalk)
			{
				if (index >= skip + size)
				{
					hasNext = true;
					break;
				}
				if (index >= skip)
				{
					commits.add(new CommitInfo(
						commit.name(),
						commit.abbreviate(8).name(),
						commit.getShortMessage(),
						commit.getAuthorIdent().getName(),
						commit.getAuthorIdent().getWhenAsInstant()));
				}
				index++;
			}
			return Optional.of(new CommitPage(commits, hasNext));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public Optional<CommitInfo> commit(Path barePath, String rev)
	{
		try (Repository repo = open(barePath); RevWalk revWalk = new RevWalk(repo))
		{
			RevCommit commit = resolveCommit(repo, revWalk, rev);
			if (commit == null)
			{
				return Optional.empty();
			}
			return Optional.of(new CommitInfo(
				commit.name(),
				commit.abbreviate(8).name(),
				commit.getShortMessage(),
				commit.getAuthorIdent().getName(),
				commit.getAuthorIdent().getWhenAsInstant()));
		}
		catch (MissingObjectException notFound)
		{
			// a well-formed but unknown id (or the all-zero id) resolves to a missing object rather than null
			return Optional.empty();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public int commitCount(Path barePath, String ref)
	{
		try (Repository repo = open(barePath); RevWalk revWalk = new RevWalk(repo))
		{
			RevCommit start = resolveCommit(repo, revWalk, ref);
			if (start == null)
			{
				return 0;
			}
			revWalk.markStart(start);
			int count = 0;
			for (RevCommit ignored : revWalk)
			{
				count++;
			}
			return count;
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * The most prolific commit authors on the given ref, most commits first (ties broken by email for a
	 * stable order). Authors are keyed by lower-cased email so one person committing under varying display
	 * names is counted once; the display name kept is the one seen on their newest commit reached first.
	 */
	public List<Contributor> contributors(Path barePath, String ref, int limit)
	{
		try (Repository repo = open(barePath); RevWalk revWalk = new RevWalk(repo))
		{
			RevCommit start = resolveCommit(repo, revWalk, ref);
			if (start == null)
			{
				return List.of();
			}
			revWalk.markStart(start);
			Map<String, Integer> counts = new HashMap<>();
			Map<String, String> names = new HashMap<>();
			for (RevCommit commit : revWalk)
			{
				var ident = commit.getAuthorIdent();
				String email = ident.getEmailAddress() == null ? "" : ident.getEmailAddress().toLowerCase(Locale.ROOT);
				counts.merge(email, 1, Integer::sum);
				names.putIfAbsent(email, ident.getName());
			}
			return counts.entrySet().stream()
				.map(entry -> new Contributor(names.get(entry.getKey()), entry.getKey(), entry.getValue()))
				.sorted(Comparator.comparingInt(Contributor::commits).reversed().thenComparing(Contributor::email))
				.limit(limit)
				.toList();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	public List<BranchInfo> branches(Path barePath)
	{
		String defaultBranch = defaultBranch(barePath);
		return refs(barePath, Constants.R_HEADS).stream()
			.map(name -> new BranchInfo(name, name.equals(defaultBranch)))
			.toList();
	}

	public List<String> tags(Path barePath)
	{
		return refs(barePath, Constants.R_TAGS);
	}

	private List<String> refs(Path barePath, String prefix)
	{
		try (Repository repo = open(barePath))
		{
			return repo.getRefDatabase().getRefsByPrefix(prefix).stream()
				.map(ref -> ref.getName().substring(prefix.length()))
				.sorted()
				.toList();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}

	private static RevCommit resolveCommit(Repository repo, RevWalk revWalk, String ref) throws IOException
	{
		ObjectId id = repo.resolve(ref + "^{commit}");
		return id == null ? null : revWalk.parseCommit(id);
	}

	private static Repository open(Path barePath) throws IOException
	{
		return new FileRepositoryBuilder().setGitDir(barePath.toFile()).setMustExist(true).build();
	}

}
