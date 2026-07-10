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

	// Exactly one of ownerUser/ownerOrg is set (DB CHECK repositories_exactly_one_owner). Both
	// owner kinds share one handle namespace, so {owner}/{name} URLs stay unambiguous.
	@ManyToOne
	public User ownerUser;

	@ManyToOne
	public Organisation ownerOrg;

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

	/** The URL path segment of whoever owns this repository: the user's handle or the org's name. */
	public String ownerHandle()
	{
		return ownerUser != null ? ownerUser.username : ownerOrg.name;
	}

	/** The owning user's or organisation's id — keys the on-disk storage directory. */
	public UUID ownerId()
	{
		return ownerUser != null ? ownerUser.id : ownerOrg.id;
	}

	public boolean isOwnedBy(User user)
	{
		return user != null && user.id != null && ownerUser != null && user.id.equals(ownerUser.id);
	}

	public enum Visibility
	{
		PUBLIC,
		PRIVATE
	}

	public interface Repo extends PanacheRepository.Managed<Repository, UUID>
	{
		@Find
		Optional<Repository> findByOwnerUserAndName(User ownerUser, String name);

		@Find
		Optional<Repository> findByOwnerOrgAndName(Organisation ownerOrg, String name);

		@HQL("where ownerOrg = :ownerOrg order by name")
		List<Repository> findByOwnerOrg(Organisation ownerOrg);

		@HQL("select count(r) from Repository r where r.ownerOrg = :ownerOrg")
		long countByOwnerOrg(Organisation ownerOrg);

		@HQL("where ownerUser = :user or visibility = PUBLIC"
			+ " or ownerOrg.id in (select m.organisation.id from OrganisationMember m where m.user = :user)"
			+ " order by name")
		List<Repository> findVisibleTo(User user);

		@HQL("where visibility = PUBLIC order by name")
		List<Repository> findPublic();
	}

}
