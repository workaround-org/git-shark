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
import jakarta.persistence.UniqueConstraint;

/**
 * A user's membership in an organisation with one of three levels: GUEST reads private org
 * repositories, MEMBER also pushes, OWNER additionally administers members, org settings and org
 * repositories. Uniqueness is enforced per (organisation, user); an organisation always keeps at
 * least one owner.
 */
@Entity
@Table(name = "organisation_members",
	uniqueConstraints = @UniqueConstraint(columnNames = { "organisation_id", "user_id" }))
public class OrganisationMember implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Organisation organisation;

	@ManyToOne(optional = false)
	public User user;

	@Enumerated(EnumType.STRING)
	public Role role;

	public Instant createdAt = Instant.now();

	/** Ordered by increasing privilege so {@code compareTo} expresses "at least". */
	public enum Role
	{
		GUEST,
		MEMBER,
		OWNER;

		public boolean atLeast(Role other)
		{
			return compareTo(other) >= 0;
		}
	}

	public interface Repo extends PanacheRepository.Managed<OrganisationMember, UUID>
	{
		@Find
		Optional<OrganisationMember> findByOrganisationAndUser(Organisation organisation, User user);

		@HQL("where organisation = :organisation order by user.username")
		List<OrganisationMember> findByOrganisation(Organisation organisation);

		@HQL("where user = :user order by organisation.name")
		List<OrganisationMember> findByUser(User user);

		@HQL("where user = :user and role = OWNER order by organisation.name")
		List<OrganisationMember> findOwnerships(User user);

		@HQL("select count(m) from OrganisationMember m where m.organisation = :organisation and m.role = OWNER")
		long countOwners(Organisation organisation);
	}

}
