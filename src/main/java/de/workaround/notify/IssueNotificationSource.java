package de.workaround.notify;

import java.util.List;

import de.workaround.git.AccessPolicy;
import de.workaround.model.Issue;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Surfaces open issues the user is involved in — ones they authored or are assigned to — as dashboard
 * notifications. Items in a repository the user can no longer read are dropped so a source turned
 * private never leaks through its issues.
 */
@ApplicationScoped
public class IssueNotificationSource implements NotificationSource
{
	@Inject
	Issue.Repo issues;

	@Inject
	AccessPolicy accessPolicy;

	@Override
	public List<NotificationItem> notificationsFor(User user)
	{
		if (user == null)
		{
			return List.of();
		}
		return issues.findOpenInvolving(user).stream()
			.filter(issue -> accessPolicy.canRead(user, issue.repository))
			.map(issue -> new NotificationItem("issue", "#" + issue.number + " " + issue.title, issue.repository,
				"/repos/" + issue.repository.ownerHandle() + "/" + issue.repository.name + "/issues/" + issue.number))
			.toList();
	}
}
