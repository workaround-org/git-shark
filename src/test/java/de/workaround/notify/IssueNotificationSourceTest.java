package de.workaround.notify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.Issue;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IssueNotificationSourceTest
{
	@Inject
	IssueNotificationSource source;

	private int nextNumber = 1;

	@Test
	@TestTransaction
	void surfacesOpenIssuesTheUserAuthoredOrIsAssignedTo()
	{
		User user = persistUser("notif-issue-me");
		User other = persistUser("notif-issue-other");
		Repository repo = persistRepo(other, "nir1", Repository.Visibility.PUBLIC);

		Issue authored = persistIssue(repo, user, null, "I opened this", Issue.Status.PLANNED);
		Issue assigned = persistIssue(repo, other, user, "Assigned to me", Issue.Status.IN_DEVELOPMENT);
		persistIssue(repo, other, other, "Not mine", Issue.Status.PLANNED);

		List<NotificationItem> items = source.notificationsFor(user);

		assertEquals(2, items.size(), "only the authored and assigned issues involve the user");
		assertTrue(items.stream().allMatch(i -> i.category().equals("issue")));
		assertTrue(items.stream().anyMatch(i -> i.title().contains("I opened this")));
		assertTrue(items.stream().anyMatch(i -> i.title().contains("Assigned to me")));
		assertTrue(items.stream().anyMatch(i -> i.targetUrl().equals(
			"/repos/" + other.username + "/nir1/issues/" + authored.number)));
		assertTrue(items.stream().anyMatch(i -> i.targetUrl().endsWith("/issues/" + assigned.number)));
	}

	@Test
	@TestTransaction
	void excludesDoneIssues()
	{
		User user = persistUser("notif-issue-done");
		Repository repo = persistRepo(user, "nir2", Repository.Visibility.PUBLIC);
		persistIssue(repo, user, user, "Finished", Issue.Status.DONE);

		assertTrue(source.notificationsFor(user).isEmpty(), "DONE issues are not pending attention");
	}

	@Test
	@TestTransaction
	void excludesIssuesInRepositoriesTheUserCannotRead()
	{
		User user = persistUser("notif-issue-hidden");
		User owner = persistUser("notif-issue-priv-owner");
		Repository secret = persistRepo(owner, "nir3", Repository.Visibility.PRIVATE);
		// assigned to the user, but in a private repo they have no read grant to
		persistIssue(secret, owner, user, "Secret", Issue.Status.PLANNED);

		assertTrue(source.notificationsFor(user).isEmpty(), "a private repo the user cannot read must not leak");
	}

	private Issue persistIssue(Repository repo, User author, User assignee, String title, Issue.Status status)
	{
		Issue issue = new Issue();
		issue.repository = repo;
		issue.author = author;
		issue.assignee = assignee;
		issue.title = title;
		issue.status = status;
		issue.number = nextNumber++;
		issue.persist();
		return issue;
	}

	private Repository persistRepo(User owner, String name, Repository.Visibility visibility)
	{
		Repository repo = new Repository();
		repo.name = name;
		repo.ownerUser = owner;
		repo.visibility = visibility;
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
