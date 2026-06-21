package de.workaround.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.processing.HQL;

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
 * One queued outbound delivery: an activity payload destined for a remote inbox, signed as the
 * named local actor. Drained by the scheduled delivery worker with exponential backoff; exhausted
 * deliveries become {@code FAILED} (dead-lettered) without blocking other rows.
 */
@Entity
@Table(name = "federation_delivery")
public class DeliveryTask implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@Column(columnDefinition = "text")
	public String targetInbox;

	/** The local actor to sign as: {@code actorKeyType} + {@code actorKeyRef}. */
	public String actorKeyRef;

	@Enumerated(EnumType.STRING)
	public FederationKey.ActorType actorKeyType;

	/** The {@code keyId} (actor id + {@code #main-key}) placed in the HTTP Signature. */
	@Column(columnDefinition = "text")
	public String signerKeyId;

	@Column(columnDefinition = "text")
	public String payload;

	public int attempts = 0;

	public Instant nextAttemptAt = Instant.now();

	@Enumerated(EnumType.STRING)
	public State state = State.PENDING;

	@Column(columnDefinition = "text")
	public String lastError;

	public Instant createdAt = Instant.now();

	public enum State
	{
		PENDING,
		DELIVERED,
		FAILED
	}

	public interface Repo extends PanacheRepository.Managed<DeliveryTask, UUID>
	{
		@HQL("where state = PENDING and nextAttemptAt <= :now order by nextAttemptAt")
		List<DeliveryTask> findDue(Instant now);
	}

}
