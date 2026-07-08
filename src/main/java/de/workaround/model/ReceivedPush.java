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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A {@code Push} activity received from a followed remote repository, kept so following users can
 * see what happened in repositories they follow. {@code remoteActorId} is the sending repository's
 * actor id; {@code activityId} deduplicates redeliveries.
 */
@Entity
@Table(name = "received_pushes")
public class ReceivedPush implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@Column(columnDefinition = "text")
	public String remoteActorId;

	@Column(columnDefinition = "text")
	public String activityId;

	@Column(columnDefinition = "text")
	public String summary;

	@Column(columnDefinition = "text")
	public String target;

	@Column(columnDefinition = "text")
	public String payload;

	public Instant receivedAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<ReceivedPush, UUID>
	{
		@Find
		Optional<ReceivedPush> findByActivityId(String activityId);

		@HQL("select p from ReceivedPush p where p.remoteActorId in :remoteActorIds order by p.receivedAt desc")
		List<ReceivedPush> findByRemoteActorIds(List<String> remoteActorIds);
	}

}
