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
import de.workaround.model.MergeRequestComment;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MergeRequestCommentServiceTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	MergeRequestService mergeRequests;

	@Inject
	MergeRequestCommentService comments;

	@Inject
	MergeRequestComment.Repo commentRepo;

	@Inject
	MergeRequest.Repo mrRepo;

	@Inject
	User.Repo userRepo;

	@Test
	void addStoresACommentAnchoredToADiffLine() throws Exception
	{
		User owner = persistUser("cm-alice");
		Repository repository = seed(owner, "cma");
		MergeRequest mr = mergeRequests.create(owner, repository, "MR", null, "feature", "main");

		// feature.txt is a newly added file, so its single line is an addition on the new side (line 1)
		MergeRequestComment comment = comments.add(owner, mr, "feature.txt", -1, 1, "looks good");

		assertEquals("feature.txt", comment.filePath);
		assertEquals(-1, comment.oldLine);
		assertEquals(1, comment.newLine);
		assertEquals("looks good", comment.body);
		assertEquals(owner.id, comment.author.id);
		assertEquals(mr.id, comment.mergeRequest.id);
	}

	@Test
	void addRejectsBlankBody() throws Exception
	{
		User owner = persistUser("cm-bob");
		Repository repository = seed(owner, "cmb");
		MergeRequest mr = mergeRequests.create(owner, repository, "MR", null, "feature", "main");

		assertThrows(InvalidMergeRequestException.class, () -> comments.add(owner, mr, "feature.txt", -1, 1, "   "));
	}

	@Test
	void addRejectsALineThatIsNotPartOfTheDiff() throws Exception
	{
		User owner = persistUser("cm-cara");
		Repository repository = seed(owner, "cmc");
		MergeRequest mr = mergeRequests.create(owner, repository, "MR", null, "feature", "main");

		assertThrows(InvalidMergeRequestException.class,
			() -> comments.add(owner, mr, "does-not-exist.txt", -1, 99, "huh"));
	}

	@Test
	void anonymousCannotComment() throws Exception
	{
		User owner = persistUser("cm-dan");
		Repository repository = seed(owner, "cmd");
		MergeRequest mr = mergeRequests.create(owner, repository, "MR", null, "feature", "main");

		assertThrows(ForbiddenOperationException.class, () -> comments.add(null, mr, "feature.txt", -1, 1, "hi"));
	}

	@Test
	void anyReaderOfAPublicRepositoryCanComment() throws Exception
	{
		User owner = persistUser("cm-erin");
		User reader = persistUser("cm-frank");
		Repository repository = seed(owner, "cme");
		MergeRequest mr = mergeRequests.create(owner, repository, "MR", null, "feature", "main");

		MergeRequestComment comment = comments.add(reader, mr, "feature.txt", -1, 1, "drive-by review");

		assertEquals(reader.id, comment.author.id);
	}

	@Test
	void strangerCannotCommentOnAPrivateRepository() throws Exception
	{
		User owner = persistUser("cm-gwen");
		User stranger = persistUser("cm-hugo");
		Repository repository = seedPrivate(owner, "cmg");
		MergeRequest mr = mergeRequests.create(owner, repository, "MR", null, "feature", "main");

		assertThrows(ForbiddenOperationException.class,
			() -> comments.add(stranger, mr, "feature.txt", -1, 1, "sneaky"));
	}

	@Test
	void authorOrOwnerCanDeleteButOthersCannot() throws Exception
	{
		User owner = persistUser("cm-ivy");
		User reader = persistUser("cm-jack");
		User other = persistUser("cm-kate");
		Repository repository = seed(owner, "cmi");
		MergeRequest mr = mergeRequests.create(owner, repository, "MR", null, "feature", "main");
		MergeRequestComment byReader = comments.add(reader, mr, "feature.txt", -1, 1, "reader note");

		assertThrows(ForbiddenOperationException.class, () -> comments.delete(other, byReader));

		// the author can delete their own comment
		comments.delete(reader, byReader);
		assertNull(commentRepo.findById(byReader.id));

		// the repo owner can delete anyone's comment
		MergeRequestComment byReader2 = comments.add(reader, mr, "feature.txt", -1, 1, "another");
		comments.delete(owner, byReader2);
		assertNull(commentRepo.findById(byReader2.id));
	}

	@Test
	void listReturnsCommentsForTheMergeRequest() throws Exception
	{
		User owner = persistUser("cm-liam");
		Repository repository = seed(owner, "cml");
		MergeRequest mr = mergeRequests.create(owner, repository, "MR", null, "feature", "main");
		comments.add(owner, mr, "feature.txt", -1, 1, "one");
		comments.add(owner, mr, "feature.txt", -1, 1, "two");

		assertEquals(2, comments.list(mr).size());
	}

	@Test
	void deletingARepositoryRemovesItsMergeRequestComments() throws Exception
	{
		User owner = persistUser("cm-mia");
		Repository repository = seed(owner, "cmm");
		MergeRequest mr = mergeRequests.create(owner, repository, "MR", null, "feature", "main");
		MergeRequestComment comment = comments.add(owner, mr, "feature.txt", -1, 1, "bye");
		UUID commentId = comment.id;

		service.delete(owner, repository);

		assertNull(commentRepo.findById(commentId), "comment must cascade-delete with its repository");
	}

	private Repository seed(User owner, String name) throws Exception
	{
		return seed(owner, name, Repository.Visibility.PUBLIC);
	}

	private Repository seedPrivate(User owner, String name) throws Exception
	{
		return seed(owner, name, Repository.Visibility.PRIVATE);
	}

	/** Repo with a base commit on main and a divergent 'feature' branch adding feature.txt. */
	private Repository seed(User owner, String name, Repository.Visibility visibility) throws Exception
	{
		Repository repository = service.create(owner, name, visibility, null);
		Path bare = service.repositoryPath(repository);
		GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)));
		Path work = Files.createTempDirectory("cmseed");
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
