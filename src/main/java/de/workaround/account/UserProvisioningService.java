package de.workaround.account;

import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Creates or updates the local user record from OIDC token claims. The OIDC subject is the
 * stable identity; username, display name and email follow the identity provider.
 */
@ApplicationScoped
public class UserProvisioningService
{
	@Inject
	User.Repo users;

	// Dev convenience only: Keycloak Dev Services mint a fresh OIDC subject for the seeded
	// alice/bob users on every run, so a real login would never match a pre-seeded row by
	// subject and would collide on the unique username. When enabled (%dev), an unknown subject
	// adopts the existing same-username row instead of inserting a duplicate. Off in prod —
	// trusting a username claim to re-key an account would be an account-takeover vector.
	@ConfigProperty(name = "gitshark.dev.adopt-username", defaultValue = "false")
	boolean adoptExistingUsername;

	@Transactional
	public User provision(String oidcSub, String username, String displayName, String email)
	{
		User user = users.findByOidcSubOptional(oidcSub).orElseGet(() -> provisionMissing(oidcSub, username));
		user.displayName = displayName;
		user.email = email;
		return user;
	}

	/**
	 * Provisioning for an interactive OIDC login. Unlike {@link #provision}, the handle is NOT derived
	 * from the {@code preferred_username} claim: a new user is created without a username and chooses a
	 * URL-safe one on the onboarding page. The claim is only used for the dev {@code adopt-username}
	 * path, which re-keys a seeded same-username row to the fresh subject.
	 */
	@Transactional
	public User provisionFromOidc(String oidcSub, String claimUsername, String displayName, String email)
	{
		User user = users.findByOidcSubOptional(oidcSub).orElseGet(() -> {
			if (adoptExistingUsername && claimUsername != null)
			{
				User existing = users.findByUsername(claimUsername).orElse(null);
				if (existing != null)
				{
					existing.oidcSub = oidcSub;
					return existing;
				}
			}
			User created = new User();
			created.oidcSub = oidcSub;
			// username stays null: onboarding required (see OnboardingFilter)
			created.persist();
			return created;
		});
		user.displayName = displayName;
		user.email = email;
		return user;
	}

	private User provisionMissing(String oidcSub, String username)
	{
		if (adoptExistingUsername)
		{
			User existing = users.findByUsername(username).orElse(null);
			if (existing != null)
			{
				existing.oidcSub = oidcSub;
				return existing;
			}
		}
		User created = new User();
		created.oidcSub = oidcSub;
		created.username = username;
		created.persist();
		return created;
	}

}
