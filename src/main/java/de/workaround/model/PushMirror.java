package de.workaround.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.processing.Find;

import de.workaround.mirror.EncryptedStringConverter;
import io.quarkus.hibernate.panache.PanacheEntity;
import io.quarkus.hibernate.panache.PanacheRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A configured push mirror: after every push to {@code repository}, its full ref set is replicated
 * to {@code remoteUrl} ({@code git push --mirror} semantics). The secret (HTTPS password/token or
 * SSH private key PEM) is encrypted at rest and never rendered back into the UI. {@code publicKey}
 * and {@code hostKey} are SSH-only: the generated deploy key and the remote host key pinned on
 * first successful contact.
 */
@Entity
@Table(name = "push_mirror")
public class PushMirror implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Repository repository;

	@Column(columnDefinition = "text")
	public String remoteUrl;

	@Enumerated(EnumType.STRING)
	public AuthType authType;

	public String username;

	@Convert(converter = EncryptedStringConverter.class)
	@Column(name = "encrypted_secret", columnDefinition = "text")
	public String secret;

	@Column(columnDefinition = "text")
	public String publicKey;

	@Column(columnDefinition = "text")
	public String hostKey;

	public boolean enabled = true;

	public Instant lastAttemptAt;

	public Instant lastSuccessAt;

	@Column(columnDefinition = "text")
	public String lastError;

	public Instant createdAt = Instant.now();

	public enum AuthType
	{
		HTTPS,
		SSH
	}

	public interface Repo extends PanacheRepository.Managed<PushMirror, UUID>
	{
		@Find
		List<PushMirror> findByRepository(Repository repository);
	}

}
