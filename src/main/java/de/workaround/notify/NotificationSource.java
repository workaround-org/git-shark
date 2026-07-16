package de.workaround.notify;

import java.util.List;

import de.workaround.model.User;

/**
 * Contributes notification items for a user to the dashboard. Any feature (issues, merge requests, …)
 * implements this as an {@code @ApplicationScoped} CDI bean; the {@link NotificationService} discovers
 * all implementations at runtime and aggregates their results. Issues and merge requests ship as
 * concrete sources.
 */
public interface NotificationSource
{
	List<NotificationItem> notificationsFor(User user);
}
