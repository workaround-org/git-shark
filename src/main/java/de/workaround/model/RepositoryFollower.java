package de.workaround.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.processing.Find;
import org.hibernate.annotations.processing.HQL;

import io.quarkus.hibernate.panache.PanacheEntity;
import io.quarkus.hibernate.panache.PanacheRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A remote actor following a local repository. The {@code followerActorId} is the remote actor's
 * id URL; the repository's set of followers is the delivery audience for its activities.
 */
@Entity
@Table(name = "repository_followers")
public class RepositoryFollower implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Repository repository;

	@Column(columnDefinition = "text")
	public String followerActorId;

	public Instant createdAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<RepositoryFollower, UUID>
	{
		@Find
		Optional<RepositoryFollower> findByRepositoryAndFollowerActorId(Repository repository, String followerActorId);

		@HQL("where repository = :repository order by createdAt")
		List<RepositoryFollower> findByRepository(Repository repository);
	}

}
