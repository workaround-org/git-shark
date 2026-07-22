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
 * A single workflow run (issue #2, phase 1): one execution of a workflow file triggered by an event
 * against a repository. A run owns one or more {@link ActionTask}s (jobs). Phase 1 materializes a
 * single task per run ({@code on: push}, single job); {@code needs}/matrix fan-out arrives in phase 2.
 * Runs carry a per-repository sequential {@link #number} for stable UI URLs, mirroring {@link Issue}.
 */
@Entity
@Table(name = "action_run")
public class ActionRun implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Repository repository;

	/** Per-repository, human-facing run number (#1, #2, ...) assigned on creation; unique within the repository. */
	public int number;

	/** The workflow's {@code name:} if set, else the file name; shown in the run list. */
	public String workflowName;

	/** Repository-relative path of the workflow file, e.g. {@code .forgejo/workflows/ci.yml}. */
	public String workflowFile;

	/** The triggering event, {@code push} in phase 1. */
	public String event;

	/** The git ref that triggered the run, e.g. {@code refs/heads/main}. */
	public String ref;

	/** Full commit SHA the run was created for. */
	public String commitSha;

	/** The user whose push triggered the run; null when the trigger has no associated account. */
	@ManyToOne
	public User triggeredBy;

	@Enumerated(EnumType.STRING)
	public Status status = Status.PENDING;

	public Instant createdAt = Instant.now();

	public Instant startedAt;

	public Instant finishedAt;

	/** Lifecycle shared by {@link ActionRun} and {@link ActionTask}. */
	public enum Status
	{
		PENDING("Pending"),
		RUNNING("Running"),
		SUCCESS("Success"),
		FAILURE("Failure"),
		CANCELLED("Cancelled");

		/** Human-readable label for the UI; the enum name is the stable value used in the DB. */
		public final String label;

		Status(String label)
		{
			this.label = label;
		}

		public boolean isTerminal()
		{
			return this == SUCCESS || this == FAILURE || this == CANCELLED;
		}
	}

	public interface Repo extends PanacheRepository.Managed<ActionRun, UUID>
	{
		@HQL("select r from ActionRun r where r.repository = :repository order by r.number desc")
		List<ActionRun> findByRepository(Repository repository);

		@Find
		Optional<ActionRun> findByRepositoryAndNumber(Repository repository, int number);

		@HQL("select coalesce(max(r.number), 0) from ActionRun r where r.repository = :repository")
		int maxNumber(Repository repository);
	}

}
