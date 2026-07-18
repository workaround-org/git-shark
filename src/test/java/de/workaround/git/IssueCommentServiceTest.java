package de.workaround.git;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.Issue;
import de.workaround.model.IssueComment;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class IssueCommentServiceTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	IssueService issues;

	@Inject
	IssueCommentService comments;

	@Inject
	IssueComment.Repo commentRepo;

	@Inject
	User.Repo userRepo;

	@Test
	void addStoresACommentOnTheIssue()
	{
		User owner = persistUser("icm-alice");
		Repository repository = service.create(owner, "icma", Repository.Visibility.PUBLIC, null);
		Issue issue = issues.create(owner, repository, "Topic", null);

		IssueComment comment = comments.add(owner, issue, "looks good");

		assertEquals("looks good", comment.body);
		assertEquals(owner.id, comment.author.id);
		assertEquals(issue.id, comment.issue.id);
	}

	@Test
	void addRejectsBlankBody()
	{
		User owner = persistUser("icm-bob");
		Repository repository = service.create(owner, "icmb", Repository.Visibility.PUBLIC, null);
		Issue issue = issues.create(owner, repository, "Topic", null);

		assertThrows(InvalidIssueException.class, () -> comments.add(owner, issue, "   "));
	}

	@Test
	void anonymousCannotComment()
	{
		User owner = persistUser("icm-dan");
		Repository repository = service.create(owner, "icmd", Repository.Visibility.PUBLIC, null);
		Issue issue = issues.create(owner, repository, "Topic", null);

		assertThrows(ForbiddenOperationException.class, () -> comments.add(null, issue, "hi"));
	}

	@Test
	void anyReaderOfAPublicRepositoryCanComment()
	{
		User owner = persistUser("icm-erin");
		User reader = persistUser("icm-frank");
		Repository repository = service.create(owner, "icme", Repository.Visibility.PUBLIC, null);
		Issue issue = issues.create(owner, repository, "Topic", null);

		IssueComment comment = comments.add(reader, issue, "drive-by remark");

		assertEquals(reader.id, comment.author.id);
	}

	@Test
	void strangerCannotCommentOnAPrivateRepository()
	{
		User owner = persistUser("icm-gwen");
		User stranger = persistUser("icm-hugo");
		Repository repository = service.create(owner, "icmg", Repository.Visibility.PRIVATE, null);
		Issue issue = issues.create(owner, repository, "Topic", null);

		assertThrows(ForbiddenOperationException.class, () -> comments.add(stranger, issue, "sneaky"));
	}

	@Test
	void authorOrOwnerCanDeleteButOthersCannot()
	{
		User owner = persistUser("icm-ivy");
		User reader = persistUser("icm-jack");
		User other = persistUser("icm-kate");
		Repository repository = service.create(owner, "icmi", Repository.Visibility.PUBLIC, null);
		Issue issue = issues.create(owner, repository, "Topic", null);
		IssueComment byReader = comments.add(reader, issue, "reader note");

		// a third party who is neither the author nor an owner/collaborator cannot delete it
		assertThrows(ForbiddenOperationException.class, () -> comments.delete(other, byReader));

		// the author can delete their own comment
		comments.delete(reader, byReader);
		assertNull(commentRepo.findById(byReader.id));

		// the repo owner can delete anyone's comment
		IssueComment byReader2 = comments.add(reader, issue, "another");
		comments.delete(owner, byReader2);
		assertNull(commentRepo.findById(byReader2.id));
	}

	@Test
	void listReturnsCommentsForTheIssue()
	{
		User owner = persistUser("icm-liam");
		Repository repository = service.create(owner, "icml", Repository.Visibility.PUBLIC, null);
		Issue issue = issues.create(owner, repository, "Topic", null);
		comments.add(owner, issue, "one");
		comments.add(owner, issue, "two");

		assertEquals(2, comments.list(issue).size());
	}

	@Test
	void deletingARepositoryRemovesItsIssueComments()
	{
		User owner = persistUser("icm-mia");
		Repository repository = service.create(owner, "icmm", Repository.Visibility.PUBLIC, null);
		Issue issue = issues.create(owner, repository, "Topic", null);
		IssueComment comment = comments.add(owner, issue, "bye");
		UUID commentId = comment.id;

		service.delete(owner, repository);

		assertNull(commentRepo.findById(commentId), "comment must cascade-delete with its repository");
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
