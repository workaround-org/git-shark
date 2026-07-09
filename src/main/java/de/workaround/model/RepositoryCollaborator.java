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
 * Grants a local user read and write access to a repository they do not own — one flat
 * collaborator role, managed by the owner. Uniqueness is enforced per (repository, user).
 */
@Entity
@Table(name = "repository_collaborators",
	uniqueConstraints = @UniqueConstraint(columnNames = { "repository_id", "user_id" }))
public class RepositoryCollaborator implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Repository repository;

	@ManyToOne(optional = false)
	public User user;

	public Instant createdAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<RepositoryCollaborator, UUID>
	{
		@Find
		Optional<RepositoryCollaborator> findByRepositoryAndUser(Repository repository, User user);

		@HQL("where repository = :repository order by user.username")
		List<RepositoryCollaborator> findByRepository(Repository repository);
	}

}
