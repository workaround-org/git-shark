package de.workaround.model;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.annotations.processing.Find;
import org.hibernate.annotations.processing.HQL;
import org.hibernate.generator.EventType;

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
 * A single job within an {@link ActionRun} (issue #2, phase 1). A runner claims a pending task via
 * FetchTask (which assigns {@link #runner}), executes it, streams rows through UpdateLog and reports
 * progress via UpdateTask. {@link #logLength} is the number of durably-persisted {@link ActionLog}
 * rows and doubles as the resume offset (ack index) for UpdateLog. {@link #deadline} bounds execution
 * so a vanished runner's task can be failed as a zombie.
 */
@Entity
@Table(name = "action_task")
public class ActionTask implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public ActionRun run;

	/**
	 * Surrogate, globally-unique int64 exposed as the runner.v1 {@code Task.id}; the runner echoes it
	 * in UpdateTask/UpdateLog. DB-generated ({@code bigserial}); read back after insert.
	 */
	@Column(insertable = false, updatable = false)
	@Generated(event = EventType.INSERT)
	public long seq;

	/** Display name of this task, e.g. {@code build} or a matrix cell {@code build (linux)}. */
	public String name;

	/** The workflow job key (e.g. {@code build}); shared by a matrix job's cells. Used for github.job and needs. */
	public String jobId = "";

	/** The job's {@code runs-on} labels, comma-joined; empty means no constraint (any runner). */
	public String runsOn = "";

	/** Names of the jobs this task depends on (comma-joined); empty means none. */
	public String needs = "";

	/** Outputs this job reported (JSON object), accumulated across UpdateTask calls; delivered to dependents. */
	public String outputs = "{}";

	/** The expanded single-job workflow payload delivered to the runner in FetchTask; null until materialized. */
	public String payload;

	/** The runner that claimed this task via FetchTask; null while pending. Cleared if the runner is deleted. */
	@ManyToOne
	public CiRunner runner;

	@Enumerated(EnumType.STRING)
	public ActionRun.Status status = ActionRun.Status.PENDING;

	/** Count of durably-persisted log rows; the resume/ack offset for UpdateLog. */
	public int logLength = 0;

	/** When a claimed task must be finished by; a task still running past this is reclaimed as a zombie. Null while pending. */
	public Instant deadline;

	public Instant createdAt = Instant.now();

	public Instant startedAt;

	public Instant finishedAt;

	public interface Repo extends PanacheRepository.Managed<ActionTask, UUID>
	{
		@HQL("select t from ActionTask t where t.run = :run order by t.createdAt asc")
		List<ActionTask> findByRun(ActionRun run);

		@HQL("select t from ActionTask t where t.status = PENDING order by t.createdAt asc")
		List<ActionTask> listPending();

		@HQL("select t from ActionTask t where t.status = RUNNING and t.deadline is not null and t.deadline < :now")
		List<ActionTask> listRunningPastDeadline(Instant now);

		default Optional<ActionTask> findOldestPending()
		{
			return listPending().stream().findFirst();
		}

		@Find
		Optional<ActionTask> findByIdAndRunner(UUID id, CiRunner runner);

		@Find
		Optional<ActionTask> findBySeq(long seq);

		/** The highest surrogate id issued so far; the coarse {@code tasks_version} handed to runners. */
		@HQL("select coalesce(max(t.seq), 0) from ActionTask t")
		long maxSeq();
	}

}
