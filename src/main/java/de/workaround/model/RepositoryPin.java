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
import jakarta.persistence.UniqueConstraint;

/**
 * Per-user marker that a repository is pinned for quick access on the dashboard.
 * A pin affects only the owning user's view; uniqueness is enforced per (user, repository).
 */
@Entity
@Table(name = "repository_pins", uniqueConstraints = @UniqueConstraint(columnNames = { "user_id", "repository_id" }))
public class RepositoryPin implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public User user;

	@ManyToOne(optional = false)
	public Repository repository;

	public Instant createdAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<RepositoryPin, UUID>
	{
		@Find
		Optional<RepositoryPin> findByUserAndRepository(User user, Repository repository);

		@Find
		List<RepositoryPin> findByUser(User user);

		@HQL("select p.repository from RepositoryPin p where p.user = :user order by p.repository.name")
		List<Repository> findPinnedRepositories(User user);
	}

}
