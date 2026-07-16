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
 * A request to merge one branch ({@link #sourceBranch}) into another ({@link #targetBranch}) within a single
 * repository. The proposed changes are diffed live from git; only the request's metadata and lifecycle
 * ({@link Status}) live in the database. Owned by the repository and removed with it (DB-level ON DELETE CASCADE).
 */
@Entity
@Table(name = "merge_requests")
public class MergeRequest implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Repository repository;

	@ManyToOne(optional = false)
	public User author;

	/** Optional person responsible for the change; null means nobody is assigned. Cleared if that user is deleted. */
	@ManyToOne
	public User assignee;

	/** Optional person asked to review the change; null means no reviewer. Cleared if that user is deleted. */
	@ManyToOne
	public User reviewer;

	/** Per-repository, human-facing number (#1, #2, ...) assigned on creation; unique within the repository. */
	public int number;

	public String title;

	public String description;

	public String sourceBranch;

	public String targetBranch;

	@Enumerated(EnumType.STRING)
	public Status status = Status.OPEN;

	public Instant createdAt = Instant.now();

	/** Set when the request is successfully merged; null while OPEN or when CLOSED without merging. */
	public Instant mergedAt;

	public enum Status
	{
		OPEN("Open"),
		MERGED("Merged"),
		CLOSED("Closed");

		/** Human-readable label for the UI; the enum name is the stable value used in forms and the DB. */
		public final String label;

		Status(String label)
		{
			this.label = label;
		}
	}

	public interface Repo extends PanacheRepository.Managed<MergeRequest, UUID>
	{
		@HQL("select mr from MergeRequest mr join fetch mr.author left join fetch mr.assignee left join fetch mr.reviewer where mr.repository = :repository order by mr.createdAt desc")
		List<MergeRequest> findByRepository(Repository repository);

		@HQL("select mr from MergeRequest mr join fetch mr.repository where (mr.author = :user or mr.assignee = :user or mr.reviewer = :user) and mr.status = OPEN order by mr.createdAt desc")
		List<MergeRequest> findOpenInvolving(User user);

		@Find
		Optional<MergeRequest> findByRepositoryAndId(Repository repository, UUID id);

		@Find
		Optional<MergeRequest> findByRepositoryAndNumber(Repository repository, int number);

		@HQL("select count(mr) from MergeRequest mr where mr.repository = :repository and mr.status = OPEN")
		long countOpen(Repository repository);

		@HQL("select coalesce(max(mr.number), 0) from MergeRequest mr where mr.repository = :repository")
		int maxNumber(Repository repository);
	}

}
