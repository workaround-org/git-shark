package de.workaround.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.processing.Find;
import org.hibernate.annotations.processing.HQL;

import io.quarkus.hibernate.panache.PanacheEntity;
import io.quarkus.hibernate.panache.PanacheRepository;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

	// Display preference for the main content column; presets scale the layout max-width.
	@Enumerated(EnumType.STRING)
	public ContentWidth contentWidth = ContentWidth.FULL;

	public boolean hasAvatar()
	{
		return avatarContentType != null;
	}

	public enum ContentWidth
	{
		FULL(""),
		COMFORTABLE("width-comfortable"),
		COMPACT("width-compact");

		public final String cssClass;

		ContentWidth(String cssClass)
		{
			this.cssClass = cssClass;
		}
	}

	public interface Repo extends PanacheRepository.Managed<User, UUID>
	{
		@Find
		User findByOidcSub(String oidcSub);

		@Find
		Optional<User> findByOidcSubOptional(String oidcSub);

		@Find
		Optional<User> findByUsername(String username);

		// Maps a commit author's email to a platform account (case-insensitive) so repository
		// contributors can be surfaced as assignee/reviewer suggestions.
		@HQL("where email is not null and lower(email) = lower(:email)")
		Optional<User> findByEmailIgnoreCase(String email);

		// Onboarded users only (username set); case-insensitive LIKE on handle and display name. The
		// caller supplies the already-lowercased %pattern% so the wildcards live in one place.
		@HQL("where username is not null and (lower(username) like :pattern or lower(displayName) like :pattern)"
			+ " order by username")
		List<User> search(String pattern);
	}

}
