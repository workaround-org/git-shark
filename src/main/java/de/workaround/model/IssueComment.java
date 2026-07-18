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
 * A free-text discussion comment on an {@link Issue}. Comments form a flat, chronological thread on the issue
 * detail page and carry only an author, a body and a creation time. Removed with the issue (and thus its
 * repository) via DB-level ON DELETE CASCADE.
 */
@Entity
@Table(name = "issue_comments")
public class IssueComment implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Issue issue;

	@ManyToOne(optional = false)
	public User author;

	public String body;

	public Instant createdAt = Instant.now();

	public interface Repo extends PanacheRepository.Managed<IssueComment, UUID>
	{
		@HQL("select c from IssueComment c join fetch c.author where c.issue = :issue order by c.createdAt")
		List<IssueComment> findByIssue(Issue issue);
	}

}
