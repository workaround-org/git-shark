package de.workaround.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.processing.Find;

import io.quarkus.hibernate.panache.PanacheEntity;
import io.quarkus.hibernate.panache.PanacheRepository;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "users")
public class User implements PanacheEntity.Managed
{
	// Explicit UUID id: WithId.AutoUUID resolves to a sequence generator in Panache Next 3.36.2
	// (generic @GeneratedValue field), which cannot produce UUIDs.
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	public String oidcSub;

	// URL-safe handle chosen by the user during onboarding. Null/blank means onboarding is still
	// pending: the user has logged in but not yet picked a handle (see OnboardingFilter).
	public String username;

	public String displayName;

	public String email;

	public Instant createdAt = Instant.now();

	// Profile picture: the bytes live on the filesystem (see AvatarService); only the content type
	// and last-update timestamp are stored here. Null content type means the user has no avatar.
	public String avatarContentType;

	public Instant avatarUpdatedAt;

	public boolean hasAvatar()
	{
		return avatarContentType != null;
	}

	public interface Repo extends PanacheRepository.Managed<User, UUID>
	{
		@Find
		User findByOidcSub(String oidcSub);

		@Find
		Optional<User> findByOidcSubOptional(String oidcSub);

		@Find
		Optional<User> findByUsername(String username);
	}

}
