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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A local user's follow of a repository on a remote instance. {@code followActivityId} is the id
 * of the {@code Follow} activity we sent; the remote's {@code Accept} references it to confirm the
 * follow ({@code PENDING} &#8594; {@code ACCEPTED}).
 */
@Entity
@Table(name = "remote_follows")
public class RemoteFollow implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public User user;

	@Column(columnDefinition = "text")
	public String remoteActorId;

	@Column(columnDefinition = "text")
	public String followActivityId;

	@Enumerated(EnumType.STRING)
	public State state = State.PENDING;

	public Instant createdAt = Instant.now();

	/** Template convenience: whether the remote has confirmed the follow. */
	public boolean isAccepted()
	{
		return state == State.ACCEPTED;
	}

	public enum State
	{
		PENDING,
		ACCEPTED
	}

	public interface Repo extends PanacheRepository.Managed<RemoteFollow, UUID>
	{
		@Find
		Optional<RemoteFollow> findByUserAndRemoteActorId(User user, String remoteActorId);

		@Find
		Optional<RemoteFollow> findByFollowActivityId(String followActivityId);

		@Find
		List<RemoteFollow> findByRemoteActorId(String remoteActorId);

		@HQL("select f from RemoteFollow f where f.user = :user order by f.createdAt")
		List<RemoteFollow> findByUser(User user);
	}

}
