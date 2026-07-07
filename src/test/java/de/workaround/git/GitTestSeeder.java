package de.workaround.git;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.RefSpec;

/** Seeds bare test repositories through a temporary working clone over file://. */
public final class GitTestSeeder
{
	private GitTestSeeder()
	{
	}

	public static void seed(Path barePath, Map<String, byte[]> files) throws Exception
	{
		seed(barePath, files, 1);
	}

	public static void seed(Path barePath, Map<String, byte[]> files, int commits) throws Exception
	{
		Path work = Files.createTempDirectory("seed");
		try (Git git = Git.cloneRepository().setURI(barePath.toUri().toString()).setDirectory(work.toFile()).call())
		{
			for (Map.Entry<String, byte[]> file : files.entrySet())
			{
				Path target = work.resolve(file.getKey());
				Files.createDirectories(target.getParent() == null ? work : target.getParent());
				Files.write(target, file.getValue());
			}
			git.add().addFilepattern(".").call();
			commit(git, "commit 1");
			for (int i = 2; i <= commits; i++)
			{
				Files.writeString(work.resolve("counter.txt"), "revision " + i + "\n");
				git.add().addFilepattern(".").call();
				commit(git, "commit " + i);
			}
			git.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
		}
	}

	/** Pushes a single commit with the given message to refs/heads/main and returns its object id. */
	public static ObjectId seedCommit(Path barePath, String message) throws Exception
	{
		Path work = Files.createTempDirectory("seed");
		try (Git git = Git.cloneRepository().setURI(barePath.toUri().toString()).setDirectory(work.toFile()).call())
		{
			Files.writeString(work.resolve("seed.txt"), message);
			git.add().addFilepattern(".").call();
			RevCommit commit = git.commit().setMessage(message).setSign(false)
				.setAuthor("seed", "seed@example.com").setCommitter("seed", "seed@example.com").call();
			git.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
			return commit.getId();
		}
	}

	private static void commit(Git git, String message) throws Exception
	{
		git.commit().setMessage(message).setSign(false)
			.setAuthor("seed", "seed@example.com").setCommitter("seed", "seed@example.com").call();
	}

}
