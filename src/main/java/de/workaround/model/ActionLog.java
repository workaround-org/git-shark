package de.workaround.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.processing.HQL;

import io.quarkus.hibernate.panache.PanacheEntity;
import io.quarkus.hibernate.panache.PanacheRepository;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One log row of an {@link ActionTask} (issue #2, phase 1). Rows are appended by UpdateLog in a
 * runner-supplied, contiguous {@link #lineIndex} order (0-based); the resume protocol replays from
 * {@link ActionTask#logLength}. Rows are removed with their task (DB-level ON DELETE CASCADE).
 */
@Entity
@Table(name = "action_log")
public class ActionLog implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public ActionTask task;

	/** 0-based position of this row within the task's log stream. */
	public int lineIndex;

	public String content;

	public Instant timestamp = Instant.now();

	public interface Repo extends PanacheRepository.Managed<ActionLog, UUID>
	{
		@HQL("select l from ActionLog l where l.task = :task order by l.lineIndex asc")
		List<ActionLog> findByTask(ActionTask task);

		@HQL("select count(l) from ActionLog l where l.task = :task")
		long countByTask(ActionTask task);
	}

}
