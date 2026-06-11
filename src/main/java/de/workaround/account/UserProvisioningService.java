package de.workaround.account;

import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Creates or updates the local user record from OIDC token claims. The OIDC subject is the
 * stable identity; username, display name and email follow the identity provider.
 */
@ApplicationScoped
public class UserProvisioningService
{
	@Inject
	User.Repo users;

	@Transactional
	public User provision(String oidcSub, String username, String displayName, String email)
	{
		User user = users.findByOidcSubOptional(oidcSub).orElseGet(() -> {
			User created = new User();
			created.oidcSub = oidcSub;
			created.username = username;
			created.persist();
			return created;
		});
		user.displayName = displayName;
		user.email = email;
		return user;
	}

}
