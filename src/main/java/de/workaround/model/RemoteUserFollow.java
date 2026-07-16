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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A local user's follow of a remote {@code Person}. Following a user is a convenience over following
 * each of their repositories: on creation we fan out to a {@link RemoteFollow} per public repository
 * in the remote's repositories collection (each tagged with {@code viaUserActorId} = this actor id).
 * The set is a snapshot at follow time — repositories the remote adds later are not picked up until a
 * re-sync.
 */
@Entity
@Table(name = "remote_user_follows")
public class RemoteUserFollow implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public User user;

	@Column(columnDefinition = "text")
	public String remoteUserActorId;

	/** The human-facing handle the user entered ({@code username@host}) or the actor id if unknown. */
	@Column(columnDefinition = "text")
	public String handle;

	public Instant createdAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<RemoteUserFollow, UUID>
	{
		@Find
		Optional<RemoteUserFollow> findByUserAndRemoteUserActorId(User user, String remoteUserActorId);

		@HQL("select f from RemoteUserFollow f where f.user = :user order by f.createdAt")
		List<RemoteUserFollow> findByUser(User user);
	}

}
