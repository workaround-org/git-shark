package de.workaround.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.processing.Find;

import io.quarkus.hibernate.panache.PanacheEntity;
import io.quarkus.hibernate.panache.PanacheRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Cached view of a fetched remote actor: its inbox (for delivery) and public key (for signature
 * verification). Refreshed on a TTL by the ActivityPub client.
 */
@Entity
@Table(name = "remote_actors")
public class RemoteActor implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@Column(columnDefinition = "text")
	public String actorId;

	@Column(columnDefinition = "text")
	public String inbox;

	@Column(columnDefinition = "text")
	public String publicKeyPem;

	public Instant fetchedAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<RemoteActor, UUID>
	{
		@Find
		Optional<RemoteActor> findByActorId(String actorId);
	}

}
