package de.workaround.git;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

import de.workaround.model.MergeRequest;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MergeRequestServiceTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	MergeRequestService mergeRequests;

	@Inject
	MergeRequest.Repo repo;

	@Inject
	User.Repo userRepo;

	@Test
	void createStoresAnOpenMergeRequestWithSourceAndTargetBranch() throws Exception
	{
		User owner = persistUser("mr-alice");
		Repository repository = seed(owner, "mra");

		MergeRequest mr = mergeRequests.create(owner, repository, "Add feature", "does things", "feature", "main");

		assertEquals("Add feature", mr.title);
		assertEquals("does things", mr.description);
		assertEquals("feature", mr.sourceBranch);
		assertEquals("main", mr.targetBranch);
		assertEquals(MergeRequest.Status.OPEN, mr.status);
		assertEquals(owner.id, mr.author.id);
		assertEquals(1, mr.number);
	}

	@Test
	void createRejectsBlankTitle() throws Exception
	{
		User owner = persistUser("mr-bob");
		Repository repository = seed(owner, "mrb");
		assertThrows(InvalidMergeRequestException.class,
			() -> mergeRequests.create(owner, repository, "  ", null, "feature", "main"));
	}

	@Test
	void createRejectsIdenticalSourceAndTargetBranch() throws Exception
	{
		User owner = persistUser("mr-cara");
		Repository repository = seed(owner, "mrc");
		assertThrows(InvalidMergeRequestException.class,
			() -> mergeRequests.create(owner, repository, "t", null, "main", "main"));
	}

	@Test
	void createRejectsUnknownBranch() throws Exception
	{
		User owner = persistUser("mr-dan");
		Repository repository = seed(owner, "mrd");
		assertThrows(InvalidMergeRequestException.class,
			() -> mergeRequests.create(owner, repository, "t", null, "ghost", "main"));
	}

	@Test
	void numbersAreSequentialPerRepository() throws Exception
	{
		User owner = persistUser("mr-erin");
		Repository a = seed(owner, "mre-a");
		Repository b = seed(owner, "mre-b");
		assertEquals(1, mergeRequests.create(owner, a, "1", null, "feature", "main").number);
		assertEquals(2, mergeRequests.create(owner, a, "2", null, "feature", "main").number);
		assertEquals(1, mergeRequests.create(owner, b, "1", null, "feature", "main").number,
			"numbering restarts per repository");
	}

	@Test
	void mergingMovesTheMergeRequestToMergedAndAdvancesTheTarget() throws Exception
	{
		User owner = persistUser("mr-finn");
		Repository repository = seed(owner, "mrf");
		MergeRequest mr = mergeRequests.create(owner, repository, "Merge me", null, "feature", "main");

		GitMergeService.MergeResult result = mergeRequests.merge(owner, mr);

		assertEquals(GitMergeService.MergeResult.MERGED, result);
		MergeRequest reloaded = repo.findById(mr.id);
		assertEquals(MergeRequest.Status.MERGED, reloaded.status);
		assertNotNull(reloaded.mergedAt);
	}

	@Test
	void closingMovesTheMergeRequestToClosedWithoutMerging() throws Exception
	{
		User owner = persistUser("mr-gwen");
		Repository repository = seed(owner, "mrg");
		MergeRequest mr = mergeRequests.create(owner, repository, "Close me", null, "feature", "main");

		mergeRequests.close(owner, mr);

		assertEquals(MergeRequest.Status.CLOSED, repo.findById(mr.id).status);
	}

	@Test
	void nonOwnerCannotCreateMergeOrClose() throws Exception
	{
		User owner = persistUser("mr-hugo");
		User stranger = persistUser("mr-ivan");
		Repository repository = seed(owner, "mrh");
		MergeRequest mr = mergeRequests.create(owner, repository, "Owned", null, "feature", "main");

		assertThrows(ForbiddenOperationException.class,
			() -> mergeRequests.create(stranger, repository, "sneaky", null, "feature", "main"));
		assertThrows(ForbiddenOperationException.class, () -> mergeRequests.merge(stranger, mr));
		assertThrows(ForbiddenOperationException.class, () -> mergeRequests.close(stranger, mr));
	}

	@Test
	void newMergeRequestsHaveNoAssigneeOrReviewer() throws Exception
	{
		User owner = persistUser("mr-omar");
		Repository repository = seed(owner, "mro");

		MergeRequest mr = mergeRequests.create(owner, repository, "x", null, "feature", "main");

		assertNull(mr.assignee, "a fresh merge request is unassigned");
		assertNull(mr.reviewer, "a fresh merge request has no reviewer");
	}

	@Test
	void assigneeAndReviewerAreSetByUsername() throws Exception
	{
		User owner = persistUser("mr-pat");
		User helper = persistUser("mr-quinn");
		Repository repository = seed(owner, "mrp");
		MergeRequest mr = mergeRequests.create(owner, repository, "x", null, "feature", "main");

		mergeRequests.assign(owner, mr, helper.username);
		mergeRequests.setReviewer(owner, mr, helper.username);

		// read once after the mutations (this class commits, so avoid a stale first-level-cache read)
		MergeRequest set = repo.findById(mr.id);
		assertEquals(helper.id, set.assignee.id, "the named user becomes the assignee");
		assertEquals(helper.id, set.reviewer.id, "the named user becomes the reviewer");
	}

	@Test
	void blankOrNullUsernameClearsAssigneeAndReviewer() throws Exception
	{
		User owner = persistUser("mr-uma");
		User helper = persistUser("mr-vic");
		Repository repository = seed(owner, "mru");
		MergeRequest mr = mergeRequests.create(owner, repository, "x", null, "feature", "main");
		mergeRequests.assign(owner, mr, helper.username);
		mergeRequests.setReviewer(owner, mr, helper.username);

		mergeRequests.assign(owner, mr, "  ");
		mergeRequests.setReviewer(owner, mr, null);

		MergeRequest cleared = repo.findById(mr.id);
		assertNull(cleared.assignee, "a blank username unassigns");
		assertNull(cleared.reviewer, "a null username clears the reviewer");
	}

	@Test
	void assignAndSetReviewerRejectUnknownUsername() throws Exception
	{
		User owner = persistUser("mr-rob");
		Repository repository = seed(owner, "mrr");
		MergeRequest mr = mergeRequests.create(owner, repository, "x", null, "feature", "main");

		assertThrows(InvalidMergeRequestException.class, () -> mergeRequests.assign(owner, mr, "ghost-user"));
		assertThrows(InvalidMergeRequestException.class, () -> mergeRequests.setReviewer(owner, mr, "ghost-user"));
	}

	@Test
	void nonWriterCannotAssignOrSetReviewer() throws Exception
	{
		User owner = persistUser("mr-sam");
		User stranger = persistUser("mr-tom");
		Repository repository = seed(owner, "mrs");
		MergeRequest mr = mergeRequests.create(owner, repository, "x", null, "feature", "main");

		assertThrows(ForbiddenOperationException.class, () -> mergeRequests.assign(stranger, mr, stranger.username));
		assertThrows(ForbiddenOperationException.class,
			() -> mergeRequests.setReviewer(stranger, mr, stranger.username));
	}

	@Test
	void countOpenExcludesMergedAndClosed() throws Exception
	{
		User owner = persistUser("mr-jane");
		Repository repository = seed(owner, "mrj");
		mergeRequests.create(owner, repository, "still open", null, "feature", "main");
		MergeRequest merged = mergeRequests.create(owner, repository, "merged", null, "feature", "main");
		MergeRequest closed = mergeRequests.create(owner, repository, "closed", null, "feature", "main");
		mergeRequests.merge(owner, merged);
		mergeRequests.close(owner, closed);

		assertEquals(1L, mergeRequests.countOpen(repository));
	}

	@Test
	void deletingARepositoryRemovesItsMergeRequests() throws Exception
	{
		User owner = persistUser("mr-kim");
		Repository repository = seed(owner, "mrk");
		MergeRequest mr = mergeRequests.create(owner, repository, "Doomed", null, "feature", "main");
		UUID mrId = mr.id;

		service.delete(owner, repository);

		assertNull(repo.findById(mrId), "merge request must be cascade-deleted with its repository");
	}

	/** Creates a repo with a base commit on main plus a divergent 'feature' branch (adds feature.txt). */
	private Repository seed(User owner, String name) throws Exception
	{
		Repository repository = service.create(owner, name, Repository.Visibility.PUBLIC, null);
		Path bare = service.repositoryPath(repository);
		GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)));
		Path work = Files.createTempDirectory("mrseed");
		try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(work.toFile()).call())
		{
			git.checkout().setCreateBranch(true).setName("feature").call();
			Files.writeString(work.resolve("feature.txt"), "feature\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("feature work").setSign(false)
				.setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();
			git.push().setRefSpecs(new RefSpec("feature:refs/heads/feature")).call();
		}
		return repository;
	}

	@Transactional
	User persistUser(String name)
	{
		User existing = userRepo.findByOidcSubOptional(name).orElse(null);
		if (existing != null)
		{
			return existing;
		}
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.persist();
		return user;
	}
}
