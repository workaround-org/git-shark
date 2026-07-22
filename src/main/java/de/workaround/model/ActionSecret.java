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
 * A repository-scoped CI secret (issue #2, phase 2). The value is stored encrypted (the same
 * {@code SecretCrypto} envelope as push-mirror credentials) and decrypted only when delivered to a
 * runner in FetchTask. Unique by {@link #name} within a repository; removed with it (cascade).
 */
@Entity
@Table(name = "action_secret")
public class ActionSecret implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Repository repository;

	public String name;

	public String valueEncrypted;

	public Instant createdAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<ActionSecret, UUID>
	{
		@HQL("select s from ActionSecret s where s.repository = :repository order by s.name asc")
		List<ActionSecret> findByRepository(Repository repository);

		@Find
		Optional<ActionSecret> findByRepositoryAndName(Repository repository, String name);
	}

}
