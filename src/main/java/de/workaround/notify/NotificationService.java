package de.workaround.notify;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jboss.logging.Logger;

import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Aggregates notification items from every registered {@link NotificationSource}. Sources are isolated:
 * a failing source is logged and skipped so it can never break the dashboard. With no registered
 * sources the result is simply empty.
 */
@ApplicationScoped
public class NotificationService
{
	private static final Logger LOG = Logger.getLogger(NotificationService.class);

	private static final Comparator<NotificationItem> ORDER =
		Comparator.comparing(NotificationItem::category).thenComparing(NotificationItem::title);

	@Inject
	Instance<NotificationSource> sources;

	public List<NotificationItem> notificationsFor(User user)
	{
		return aggregate(user, sources);
	}

	List<NotificationItem> aggregate(User user, Iterable<NotificationSource> sources)
	{
		List<NotificationItem> items = new ArrayList<>();
		for (NotificationSource source : sources)
		{
			try
			{
				List<NotificationItem> produced = source.notificationsFor(user);
				if (produced != null)
				{
					items.addAll(produced);
				}
			}
			catch (RuntimeException e)
			{
				LOG.errorf(e, "Notification source %s failed; skipping it", source.getClass().getName());
			}
		}
		items.sort(ORDER);
		return items;
	}

}
