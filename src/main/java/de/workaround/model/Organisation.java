package de.workaround.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.processing.Find;

import io.quarkus.hibernate.panache.PanacheEntity;
import io.quarkus.hibernate.panache.PanacheRepository;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A shared repository namespace owned by its members. The name lives in the same handle namespace
 * as {@link User#username} (one URL scheme for user and org repos), so creation collision-checks
 * both tables via the handle service.
 */
@Entity
@Table(name = "organisations")
public class Organisation implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	public String name;

	public String displayName;

	public Instant createdAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<Organisation, UUID>
	{
		@Find
		Optional<Organisation> findByName(String name);
	}

}
