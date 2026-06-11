package de.workaround.model;

import java.time.Instant;
import java.util.List;
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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "ssh_keys")
public class SshKey implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public User user;

	public String title;

	@Column(columnDefinition = "text")
	public String publicKey;

	public String fingerprint;

	public Instant createdAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<SshKey, UUID>
	{
		@Find
		Optional<SshKey> findByFingerprint(String fingerprint);

		@Find
		List<SshKey> findByUser(User user);
	}

}
