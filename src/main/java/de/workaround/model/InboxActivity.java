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
 * Idempotency log of accepted inbound activities. Presence of an activity id means it has already
 * been processed, so a redelivery is a no-op.
 */
@Entity
@Table(name = "federation_inbox")
public class InboxActivity implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@Column(columnDefinition = "text")
	public String activityId;

	public Instant receivedAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<InboxActivity, UUID>
	{
		@Find
		Optional<InboxActivity> findByActivityId(String activityId);
	}

}
