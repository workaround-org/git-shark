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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A shared registration token an admin hands to {@code forgejo-runner register}. Instance-scoped
 * and reusable (matching Gitea's global registration tokens): a runner presents it once in the
 * Register RPC body and thereafter authenticates with its own per-runner secret. Only the SHA-256
 * hash of the plaintext is stored; the plaintext is shown to the admin exactly once at creation.
 */
@Entity
@Table(name = "ci_runner_registration_token")
public class CiRunnerRegistrationToken implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	public String tokenHash;

	// The admin who generated the token; nullable so deleting the user leaves the token standing.
	@ManyToOne
	public User createdBy;

	/** Repository this token (and the runners it creates) is scoped to; null means instance-scope. */
	@ManyToOne
	public Repository repository;

	public Instant createdAt = Instant.now();

	public Instant lastUsed;

	public interface Repo extends PanacheRepository.Managed<CiRunnerRegistrationToken, UUID>
	{
		@Find
		Optional<CiRunnerRegistrationToken> findByTokenHash(String tokenHash);

		@HQL("order by createdAt desc")
		List<CiRunnerRegistrationToken> listNewestFirst();
	}

}
