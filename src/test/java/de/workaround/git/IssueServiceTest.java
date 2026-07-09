package de.workaround.git;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.Issue;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IssueServiceTest
{
	@Inject
	IssueService issueService;

	@Inject
	Issue.Repo issues;

	@Inject
	Repository.Repo repositories;

	@Inject
	EntityManager em;

	@Test
	@TestTransaction
	void createStoresIssueWithTitleDescriptionAndPlannedStatus()
	{
		User owner = persistUser("iss-alice");
		Repository repo = persistRepo(owner, "r1");

		Issue issue = issueService.create(owner, repo, "Fix login", "It is broken");

		assertEquals("Fix login", issue.title);
		assertEquals("It is broken", issue.description);
		assertEquals(Issue.Status.PLANNED, issue.status, "new issues start as PLANNED");
		assertEquals(owner.id, issue.author.id);
		assertEquals(repo.id, issue.repository.id);
	}

	@Test
	@TestTransaction
	void createRejectsBlankTitle()
	{
		User owner = persistUser("iss-bob");
		Repository repo = persistRepo(owner, "r2");

		assertThrows(InvalidIssueException.class, () -> issueService.create(owner, repo, "   ", "body"));
	}

	@Test
	@TestTransaction
	void blankDescriptionIsStoredAsNull()
	{
		User owner = persistUser("iss-cara");
		Repository repo = persistRepo(owner, "r3");

		Issue issue = issueService.create(owner, repo, "Title only", "  ");

		assertNull(issue.description);
	}

	@Test
	@TestTransaction
	void statusCanBeMovedThroughPlannedInDevelopmentDone()
	{
		User owner = persistUser("iss-dave");
		Repository repo = persistRepo(owner, "r4");
		Issue issue = issueService.create(owner, repo, "Ship it", null);

		issueService.updateStatus(owner, issue, Issue.Status.IN_DEVELOPMENT);
		assertEquals(Issue.Status.IN_DEVELOPMENT, issues.findById(issue.id).status);

		issueService.updateStatus(owner, issue, Issue.Status.DONE);
		assertEquals(Issue.Status.DONE, issues.findById(issue.id).status);
	}

	@Test
	@TestTransaction
	void updateEditsTitleAndDescription()
	{
		User owner = persistUser("iss-kate");
		Repository repo = persistRepo(owner, "r6");
		Issue issue = issueService.create(owner, repo, "Old title", "old body");

		issueService.update(owner, issue, "  New title  ", "new body");

		Issue reloaded = issues.findById(issue.id);
		assertEquals("New title", reloaded.title, "title is trimmed and updated");
		assertEquals("new body", reloaded.description);
		assertEquals(issue.number, reloaded.number, "editing must not change the number");
	}

	@Test
	@TestTransaction
	void updateRejectsBlankTitle()
	{
		User owner = persistUser("iss-liam");
		Repository repo = persistRepo(owner, "r7");
		Issue issue = issueService.create(owner, repo, "Keep me", "body");

		assertThrows(InvalidIssueException.class, () -> issueService.update(owner, issue, "   ", "body"));
		assertEquals("Keep me", issues.findById(issue.id).title, "a rejected update must not change the issue");
	}

	@Test
	@TestTransaction
	void updateWithBlankDescriptionClearsIt()
	{
		User owner = persistUser("iss-mona");
		Repository repo = persistRepo(owner, "r8");
		Issue issue = issueService.create(owner, repo, "Titled", "described");

		issueService.update(owner, issue, "Titled", "   ");

		assertNull(issues.findById(issue.id).description);
	}

	@Test
	@TestTransaction
	void deleteRemovesTheIssue()
	{
		User owner = persistUser("iss-erin");
		Repository repo = persistRepo(owner, "r5");
		Issue issue = issueService.create(owner, repo, "Temporary", null);

		issueService.delete(owner, issue);

		assertTrue(issueService.find(repo, issue.id).isEmpty());
	}

	@Test
	@TestTransaction
	void issuesGetSequentialPerRepositoryNumbers()
	{
		User owner = persistUser("iss-nils");
		Repository repoA = persistRepo(owner, "rna");
		Repository repoB = persistRepo(owner, "rnb");

		assertEquals(1, issueService.create(owner, repoA, "a1", null).number);
		assertEquals(2, issueService.create(owner, repoA, "a2", null).number);
		assertEquals(1, issueService.create(owner, repoB, "b1", null).number, "numbering restarts per repository");
		assertEquals(3, issueService.create(owner, repoA, "a3", null).number);
	}

	@Test
	@TestTransaction
	void countOpenCountsPlannedAndInDevelopmentButNotDone()
	{
		User owner = persistUser("iss-jack");
		Repository repo = persistRepo(owner, "rf");
		issueService.create(owner, repo, "still planned", null);
		Issue inDev = issueService.create(owner, repo, "in progress", null);
		Issue finished = issueService.create(owner, repo, "shipped", null);
		issueService.updateStatus(owner, inDev, Issue.Status.IN_DEVELOPMENT);
		issueService.updateStatus(owner, finished, Issue.Status.DONE);

		assertEquals(2L, issueService.countOpen(repo), "planned + in-development count as open, done does not");
	}

	@Test
	@TestTransaction
	void issuesAreScopedPerRepository()
	{
		User owner = persistUser("iss-finn");
		Repository repoA = persistRepo(owner, "ra");
		Repository repoB = persistRepo(owner, "rb");
		issueService.create(owner, repoA, "Only in A", null);

		assertEquals(1, issueService.list(repoA).size());
		assertTrue(issueService.list(repoB).isEmpty(), "an issue in repo A must not appear in repo B");
	}

	@Test
	@TestTransaction
	void nonOwnerCannotCreateUpdateOrDeleteIssues()
	{
		User owner = persistUser("iss-gwen");
		User stranger = persistUser("iss-hugo");
		Repository repo = persistRepo(owner, "rc");
		Issue issue = issueService.create(owner, repo, "Owned", null);

		assertThrows(ForbiddenOperationException.class, () -> issueService.create(stranger, repo, "Sneaky", null));
		assertThrows(ForbiddenOperationException.class,
			() -> issueService.updateStatus(stranger, issue, Issue.Status.DONE));
		assertThrows(ForbiddenOperationException.class, () -> issueService.update(stranger, issue, "Hijack", null));
		assertThrows(ForbiddenOperationException.class, () -> issueService.delete(stranger, issue));
	}

	@Test
	@TestTransaction
	void deletingARepositoryRemovesItsIssues()
	{
		User owner = persistUser("iss-ivy");
		Repository repo = persistRepo(owner, "rd");
		Issue doomed = issueService.create(owner, repo, "Doomed", null);
		UUID doomedId = doomed.id;
		UUID repoId = repo.id;

		em.flush();
		em.clear();
		repositories.deleteById(repoId);
		em.flush();
		em.clear();

		// scoped to this issue rather than a global count(): other test classes commit issues that persist
		assertNull(issues.findById(doomedId), "the issue must be cascade-deleted with its repository");
	}

	private Repository persistRepo(User owner, String name)
	{
		Repository repo = new Repository();
		repo.name = name;
		repo.owner = owner;
		repo.visibility = Repository.Visibility.PUBLIC;
		repo.persist();
		return repo;
	}

	private User persistUser(String name)
	{
		User user = new User();
		user.oidcSub = name + "-" + UUID.randomUUID();
		user.username = name + "-" + UUID.randomUUID().toString().substring(0, 8);
		user.persist();
		return user;
	}

}
