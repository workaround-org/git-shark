package de.workaround.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.processing.Find;
import org.hibernate.annotations.processing.HQL;

import io.quarkus.hibernate.panache.PanacheEntity;
import io.quarkus.hibernate.panache.PanacheRepository;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A work item tracked against a single repository. Issues carry a free-text title and description
 * and move through a small fixed lifecycle ({@link Status}). They are owned by the repository they
 * belong to and are removed with it (DB-level ON DELETE CASCADE).
 */
@Entity
@Table(name = "issues")
public class Issue implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Repository repository;

	@ManyToOne(optional = false)
	public User author;

	/** Optional person responsible for the issue; null means nobody is assigned. Cleared (set null) if that user is deleted. */
	@ManyToOne
	public User assignee;

	/** Per-repository, human-facing number (#1, #2, ...) assigned on creation; unique within the repository. */
	public int number;

	public String title;

	public String description;

	@Enumerated(EnumType.STRING)
	public Status status = Status.PLANNED;

	public Instant createdAt = Instant.now();

	public enum Status
	{
		PLANNED("Planned"),
		IN_DEVELOPMENT("In development"),
		DONE("Done");

		/** Human-readable label for the UI; the enum name is the stable value used in forms and the DB. */
		public final String label;

		Status(String label)
		{
			this.label = label;
		}
	}

	public interface Repo extends PanacheRepository.Managed<Issue, UUID>
	{
		@HQL("select i from Issue i join fetch i.author left join fetch i.assignee where i.repository = :repository order by i.createdAt desc")
		List<Issue> findByRepository(Repository repository);

		@HQL("select i from Issue i join fetch i.repository where (i.author = :user or i.assignee = :user) and i.status <> DONE order by i.createdAt desc")
		List<Issue> findOpenInvolving(User user);

		@Find
		Optional<Issue> findByRepositoryAndId(Repository repository, UUID id);

		@Find
		Optional<Issue> findByRepositoryAndNumber(Repository repository, int number);

		@HQL("select count(i) from Issue i where i.repository = :repository and i.status in (PLANNED, IN_DEVELOPMENT)")
		long countOpen(Repository repository);

		@HQL("select coalesce(max(i.number), 0) from Issue i where i.repository = :repository")
		int maxNumber(Repository repository);
	}

}
