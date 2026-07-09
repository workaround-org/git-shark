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

@Entity
@Table(name = "repositories")
public class Repository implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	public String name;

	@ManyToOne(optional = false)
	public User owner;

	@Enumerated(EnumType.STRING)
	public Visibility visibility;

	public String description;

	// Custom repository image: bytes live on the filesystem (gitshark.storage.repo-images) keyed by id.
	// A null content type means no custom image, in which case the repo falls back to the owner's avatar.
	public String imageContentType;

	public Instant imageUpdatedAt;

	public Instant createdAt = Instant.now();

	public boolean hasImage()
	{
		return imageContentType != null;
	}

	public enum Visibility
	{
		PUBLIC,
		PRIVATE
	}

	public interface Repo extends PanacheRepository.Managed<Repository, UUID>
	{
		@Find
		Optional<Repository> findByOwnerAndName(User owner, String name);

		@HQL("where owner = :owner or visibility = PUBLIC order by name")
		List<Repository> findVisibleTo(User owner);

		@HQL("where visibility = PUBLIC order by name")
		List<Repository> findPublic();
	}

}
