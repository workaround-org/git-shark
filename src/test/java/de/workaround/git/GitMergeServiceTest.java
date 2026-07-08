package de.workaround.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class GitMergeServiceTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	GitMergeService merge;

	@Inject
	de.workaround.model.User.Repo userRepo;

	@Test
	void diffListsFilesChangedOnTheSourceBranchRelativeToTheTarget() throws Exception
	{
		Path bare = seededRepo("gm-diff");
		branchWithChange(bare, "feature", "hello.txt", "hello world\n");

		GitMergeService.DiffView diff = merge.diff(bare, "main", "feature").orElseThrow();

		assertEquals(1, diff.files().size());
		GitMergeService.FileDiff file = diff.files().get(0);
		assertEquals("hello.txt", file.path());
		assertTrue(file.additions() >= 1, "the new line should be counted as an addition");
		assertTrue(diff.files().get(0).lines().stream().anyMatch(l -> l.text().contains("hello world")));
	}

	@Test
	void mergeCreatesAMergeCommitAndAdvancesTheTargetBranch() throws Exception
	{
		Path bare = seededRepo("gm-merge");
		branchWithChange(bare, "feature", "feature.txt", "new feature\n");

		GitMergeService.MergeResult result = merge.merge(bare, "main", "feature", "Alice", "alice@example.com",
			"Merge feature into main");

		assertEquals(GitMergeService.MergeResult.MERGED, result);
		// the merged file is now reachable from main
		assertTrue(fileExistsOnBranch(bare, "main", "feature.txt"),
			"after merging, the feature file must be present on main");
	}

	@Test
	void mergeReportsConflictWhenBothBranchesChangeTheSameLines() throws Exception
	{
		Path bare = seededRepo("gm-conflict");
		// diverge: change base.txt on main and differently on feature
		branchWithChange(bare, "feature", "base.txt", "feature side\n");
		commitOnBranch(bare, "main", "base.txt", "main side\n");

		GitMergeService.MergeResult result = merge.merge(bare, "main", "feature", "Alice", "alice@example.com",
			"Merge");

		assertEquals(GitMergeService.MergeResult.CONFLICT, result);
	}

	@Test
	void mergeReportsUpToDateWhenSourceIsAlreadyContainedInTarget() throws Exception
	{
		Path bare = seededRepo("gm-uptodate");
		// feature points at the same commit as main -> nothing to merge
		createBranchAt(bare, "feature", "main");

		GitMergeService.MergeResult result = merge.merge(bare, "main", "feature", "Alice", "alice@example.com",
			"Merge");

		assertEquals(GitMergeService.MergeResult.UP_TO_DATE, result);
	}

	@Test
	void branchExistenceIsReported() throws Exception
	{
		Path bare = seededRepo("gm-exists");
		assertTrue(merge.branchExists(bare, "main"));
		assertFalse(merge.branchExists(bare, "does-not-exist"));
	}

	private Path seededRepo(String name) throws Exception
	{
		de.workaround.model.User owner = persistUser(name);
		de.workaround.model.Repository repo = service.create(owner, name,
			de.workaround.model.Repository.Visibility.PUBLIC, null);
		Path bare = service.repositoryPath(repo);
		GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)));
		return bare;
	}

	/** Branches off main, adds/updates one file, pushes the branch. Leaves main untouched. */
	private static void branchWithChange(Path bare, String branch, String file, String content) throws Exception
	{
		Path work = Files.createTempDirectory("mergework");
		try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(work.toFile()).call())
		{
			git.checkout().setCreateBranch(true).setName(branch).call();
			Files.writeString(work.resolve(file), content);
			git.add().addFilepattern(".").call();
			git.commit().setMessage("change on " + branch).setSign(false)
				.setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();
			git.push().setRefSpecs(new RefSpec(branch + ":refs/heads/" + branch)).call();
		}
	}

	private static void commitOnBranch(Path bare, String branch, String file, String content) throws Exception
	{
		Path work = Files.createTempDirectory("mergework");
		try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setBranch(branch)
			.setDirectory(work.toFile()).call())
		{
			Files.writeString(work.resolve(file), content);
			git.add().addFilepattern(".").call();
			git.commit().setMessage("change on " + branch).setSign(false)
				.setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();
			git.push().setRefSpecs(new RefSpec(branch + ":refs/heads/" + branch)).call();
		}
	}

	private static void createBranchAt(Path bare, String branch, String at) throws Exception
	{
		Path work = Files.createTempDirectory("mergework");
		try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setBranch(at)
			.setDirectory(work.toFile()).call())
		{
			git.push().setRefSpecs(new RefSpec(at + ":refs/heads/" + branch)).call();
		}
	}

	private static boolean fileExistsOnBranch(Path bare, String branch, String file) throws Exception
	{
		Path work = Files.createTempDirectory("mergecheck");
		try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setBranch(branch)
			.setDirectory(work.toFile()).call())
		{
			return Files.exists(work.resolve(file));
		}
	}

	@jakarta.transaction.Transactional
	de.workaround.model.User persistUser(String name)
	{
		de.workaround.model.User existing = userRepo.findByOidcSubOptional(name).orElse(null);
		if (existing != null)
		{
			return existing;
		}
		de.workaround.model.User user = new de.workaround.model.User();
		user.oidcSub = name;
		user.username = name;
		user.persist();
		return user;
	}
}
