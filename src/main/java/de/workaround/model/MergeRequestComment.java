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
 * A line-level review comment on a {@link MergeRequest}'s diff. The comment is anchored to a file
 * ({@link #filePath}) and a specific diff line via the {@code (oldLine, newLine)} pair — {@code -1} on a side
 * means the line has no counterpart there (added lines have no old number, deleted lines no new number). Removed
 * with its merge request (and thus its repository) via DB-level ON DELETE CASCADE.
 */
@Entity
@Table(name = "merge_request_comments")
public class MergeRequestComment implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public MergeRequest mergeRequest;

	@ManyToOne(optional = false)
	public User author;

	public String filePath;

	/** 1-based line number on the old side of the diff, or -1 if the anchored line is an addition. */
	public int oldLine;

	/** 1-based line number on the new side of the diff, or -1 if the anchored line is a deletion. */
	public int newLine;

	public String body;

	public Instant createdAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<MergeRequestComment, UUID>
	{
		@HQL("select c from MergeRequestComment c join fetch c.author where c.mergeRequest = :mergeRequest order by c.createdAt")
		List<MergeRequestComment> findByMergeRequest(MergeRequest mergeRequest);
	}

}
