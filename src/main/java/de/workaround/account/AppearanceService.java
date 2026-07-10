package de.workaround.account;

import java.util.Locale;

import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Persists per-user display preferences chosen on the settings page. Currently only the content
 * width preset, which scales the layout's main column (see {@link User.ContentWidth}).
 */
@ApplicationScoped
public class AppearanceService
{
	@Inject
	User.Repo users;

	@Transactional
	public void setContentWidth(User user, String preset)
	{
		User.ContentWidth parsed;
		try
		{
			parsed = User.ContentWidth.valueOf(preset == null ? "" : preset.trim().toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException e)
		{
			throw new InvalidContentWidthException("Unknown content width preset.");
		}
		User managed = users.findById(user.id);
		managed.contentWidth = parsed;
		user.contentWidth = parsed;
	}

}
