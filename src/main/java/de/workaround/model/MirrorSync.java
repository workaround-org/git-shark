package de.workaround.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One queued mirror sync. The payload is just the mirror reference — pushing the current
 * repository state is always correct, so rapid pushes coalesce into a single PENDING row per
 * mirror. Drained by the scheduled worker with exponential backoff; exhausted rows become
 * {@code FAILED} (dead-lettered) and the next repository push enqueues a fresh one.
 */
@Entity
@Table(name = "mirror_sync")
public class MirrorSync implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public PushMirror mirror;

	@Enumerated(EnumType.STRING)
	public State state = State.PENDING;

	public int attempts = 0;

	public Instant nextAttemptAt = Instant.now();

	@Column(columnDefinition = "text")
	public String lastError;

	public Instant createdAt = Instant.now();

	public enum State
	{
		PENDING,
		SYNCED,
		FAILED
	}

	public interface Repo extends PanacheRepository.Managed<MirrorSync, UUID>
	{
		@HQL("where state = PENDING and nextAttemptAt <= :now order by nextAttemptAt")
		List<MirrorSync> findDue(Instant now);

		@HQL("where mirror = :mirror and state = PENDING")
		Optional<MirrorSync> findPending(PushMirror mirror);

		@HQL("where mirror.id = :mirrorId")
		List<MirrorSync> findByMirrorId(UUID mirrorId);
	}

}
