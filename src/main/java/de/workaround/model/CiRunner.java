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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A CI/CD runner registered against this instance via the Forgejo/Gitea runner.v1 Connect protocol.
 * The runner authenticates every post-registration call with {@link #uuid} + a secret whose SHA-256
 * hash is kept in {@link #tokenHash}; the plaintext secret is returned to the runner only once, at
 * registration. A runner may be scoped to a single {@link #repository} (null means instance-scope,
 * serving any repository).
 */
@Entity
@Table(name = "ci_runner")
public class CiRunner implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	// The identifier the runner echoes in the x-runner-uuid header; distinct from the DB primary key
	// so the runner never learns our internal id.
	public String uuid;

	public String tokenHash;

	public String name;

	// Comma-joined labels advertised via Register/Declare; empty string means none.
	public String labels = "";

	public String version;

	@Enumerated(EnumType.STRING)
	public Status status = Status.IDLE;

	public boolean ephemeral;

	/** Repository this runner is scoped to; null means instance-scope (any repository). */
	@ManyToOne
	public Repository repository;

	public Instant lastSeen;

	public Instant createdAt = Instant.now();

	public enum Status
	{
		UNSPECIFIED,
		IDLE,
		ACTIVE,
		OFFLINE
	}

	public interface Repo extends PanacheRepository.Managed<CiRunner, UUID>
	{
		@Find
		Optional<CiRunner> findByUuid(String uuid);

		@Find
		Optional<CiRunner> findByUuidAndTokenHash(String uuid, String tokenHash);

		@HQL("order by createdAt desc")
		List<CiRunner> listNewestFirst();

		@HQL("select r from CiRunner r where r.repository = :repository order by r.createdAt desc")
		List<CiRunner> findByRepository(Repository repository);
	}

}
