package de.workaround.notify;

import de.workaround.model.Repository;

/**
 * A single item awaiting the current user's attention on the dashboard.
 *
 * @param category   short type label, e.g. {@code "issue"} or {@code "merge-request"}
 * @param title      human-readable description shown to the user
 * @param repository the related repository, or {@code null} if not repository-scoped
 * @param targetUrl  where following the item takes the user
 */
public record NotificationItem(String category, String title, Repository repository, String targetUrl)
{
}
