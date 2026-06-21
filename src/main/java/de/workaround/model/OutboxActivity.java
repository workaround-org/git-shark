package de.workaround.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.processing.Find;
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
 * An activity published by a local actor, exposed through that actor's outbox OrderedCollection.
 */
@Entity
@Table(name = "federation_outbox")
public class OutboxActivity implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@Enumerated(EnumType.STRING)
	public FederationKey.ActorType actorType;

	public String actorRef;

	@Column(columnDefinition = "text")
	public String activityId;

	@Column(columnDefinition = "text")
	public String payload;

	public Instant publishedAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<OutboxActivity, UUID>
	{
		@HQL("where actorType = :actorType and actorRef = :actorRef order by publishedAt desc")
		List<OutboxActivity> findByActor(FederationKey.ActorType actorType, String actorRef);

		@Find
		Optional<OutboxActivity> findByActivityId(String activityId);
	}

}
