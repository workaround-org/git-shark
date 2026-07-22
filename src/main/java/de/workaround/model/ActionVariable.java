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
 * A repository-scoped CI variable (issue #2, phase 2): plain (non-secret) configuration delivered to
 * runners in FetchTask. Unique by {@link #name} within a repository; removed with it (cascade).
 */
@Entity
@Table(name = "action_variable")
public class ActionVariable implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Repository repository;

	public String name;

	public String value;

	public Instant createdAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<ActionVariable, UUID>
	{
		@HQL("select v from ActionVariable v where v.repository = :repository order by v.name asc")
		List<ActionVariable> findByRepository(Repository repository);

		@Find
		Optional<ActionVariable> findByRepositoryAndName(Repository repository, String name);
	}

}
