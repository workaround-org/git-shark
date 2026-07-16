package de.workaround.notify;

import java.util.List;

import de.workaround.git.AccessPolicy;
import de.workaround.model.MergeRequest;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Surfaces open merge requests the user is involved in — ones they authored, are assigned to, or are
 * asked to review — as dashboard notifications. Items in a repository the user can no longer read are
 * dropped so a source turned private never leaks through its merge requests.
 */
@ApplicationScoped
public class MergeRequestNotificationSource implements NotificationSource
{
	@Inject
	MergeRequest.Repo mergeRequests;

	@Inject
	AccessPolicy accessPolicy;

	@Override
	public List<NotificationItem> notificationsFor(User user)
	{
		if (user == null)
		{
			return List.of();
		}
		return mergeRequests.findOpenInvolving(user).stream()
			.filter(mr -> accessPolicy.canRead(user, mr.repository))
			.map(mr -> new NotificationItem("merge-request", "!" + mr.number + " " + mr.title, mr.repository,
				"/repos/" + mr.repository.ownerHandle() + "/" + mr.repository.name + "/merge-requests/" + mr.number))
			.toList();
	}
}
