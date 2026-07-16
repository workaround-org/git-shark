package de.workaround.notify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.MergeRequest;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class MergeRequestNotificationSourceTest
{
	@Inject
	MergeRequestNotificationSource source;

	private int nextNumber = 1;

	@Test
	@TestTransaction
	void surfacesOpenMergeRequestsTheUserAuthoredIsAssignedToOrReviews()
	{
		User user = persistUser("notif-mr-me");
		User other = persistUser("notif-mr-other");
		Repository repo = persistRepo(other, "nmr1", Repository.Visibility.PUBLIC);

		persistMr(repo, user, null, null, "I opened this", MergeRequest.Status.OPEN);
		persistMr(repo, other, user, null, "Assigned to me", MergeRequest.Status.OPEN);
		persistMr(repo, other, null, user, "Review requested", MergeRequest.Status.OPEN);
		persistMr(repo, other, other, other, "Not mine", MergeRequest.Status.OPEN);

		List<NotificationItem> items = source.notificationsFor(user);

		assertEquals(3, items.size(), "authored, assigned and to-review MRs involve the user");
		assertTrue(items.stream().allMatch(i -> i.category().equals("merge-request")));
		assertTrue(items.stream().anyMatch(i -> i.title().contains("I opened this")));
		assertTrue(items.stream().anyMatch(i -> i.title().contains("Assigned to me")));
		assertTrue(items.stream().anyMatch(i -> i.title().contains("Review requested")));
		assertTrue(items.stream().allMatch(i -> i.targetUrl().contains("/merge-requests/")));
	}

	@Test
	@TestTransaction
	void excludesMergedAndClosedMergeRequests()
	{
		User user = persistUser("notif-mr-done");
		Repository repo = persistRepo(user, "nmr2", Repository.Visibility.PUBLIC);
		persistMr(repo, user, user, user, "Merged", MergeRequest.Status.MERGED);
		persistMr(repo, user, user, user, "Closed", MergeRequest.Status.CLOSED);

		assertTrue(source.notificationsFor(user).isEmpty(), "only open merge requests are pending attention");
	}

	@Test
	@TestTransaction
	void excludesMergeRequestsInRepositoriesTheUserCannotRead()
	{
		User user = persistUser("notif-mr-hidden");
		User owner = persistUser("notif-mr-priv-owner");
		Repository secret = persistRepo(owner, "nmr3", Repository.Visibility.PRIVATE);
		persistMr(secret, owner, user, user, "Secret", MergeRequest.Status.OPEN);

		assertTrue(source.notificationsFor(user).isEmpty(), "a private repo the user cannot read must not leak");
	}

	private MergeRequest persistMr(Repository repo, User author, User assignee, User reviewer, String title,
		MergeRequest.Status status)
	{
		MergeRequest mr = new MergeRequest();
		mr.repository = repo;
		mr.author = author;
		mr.assignee = assignee;
		mr.reviewer = reviewer;
		mr.title = title;
		mr.sourceBranch = "feature";
		mr.targetBranch = "main";
		mr.status = status;
		mr.number = nextNumber++;
		mr.persist();
		return mr;
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
