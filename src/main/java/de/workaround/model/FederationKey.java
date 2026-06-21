package de.workaround.model;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.processing.Find;

import io.quarkus.hibernate.panache.PanacheEntity;
import io.quarkus.hibernate.panache.PanacheRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persistent signing keypair for a local federation actor. {@code actorRef} identifies the actor
 * within its type: a repository id, a user id, or the literal {@code "instance"}.
 */
@Entity
@Table(name = "federation_keys")
public class FederationKey implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@Enumerated(EnumType.STRING)
	public ActorType actorType;

	public String actorRef;

	@Column(columnDefinition = "text")
	public String publicPem;

	@Column(columnDefinition = "text")
	public String privatePem;

	public Instant createdAt = Instant.now();

	public enum ActorType
	{
		REPOSITORY,
		PERSON,
		INSTANCE
	}

	public interface Repo extends PanacheRepository.Managed<FederationKey, UUID>
	{
		@Find
		Optional<FederationKey> findByActorTypeAndActorRef(ActorType actorType, String actorRef);
	}

}
