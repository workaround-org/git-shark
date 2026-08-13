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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A published version of a repository, pinned to exactly one git tag and carrying human-facing release notes.
 * The tag itself lives in git, not here — this row only annotates it with a title, notes and a prerelease flag,
 * so deleting a release never touches the repository's history. Owned by the repository and removed with it
 * (DB-level ON DELETE CASCADE).
 */
@Entity
@Table(name = "releases")
public class Release implements PanacheEntity.Managed
{
	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	public UUID id;

	@ManyToOne(optional = false)
	public Repository repository;

	@ManyToOne(optional = false)
	public User author;

	/** The git tag this release publishes; unique within the repository. */
	public String tagName;

	public String title;

	/** Release notes, rendered as Markdown; null when the author left them empty. */
	public String body;

	/** The commit the tag resolved to when the release was published, so the page can link the exact revision. */
	public String commitId;

	/** A prerelease is listed like any other release but never counts as the repository's latest one. */
	public boolean prerelease;

	public Instant createdAt = Instant.now();

	/** The first 7 characters of {@link #commitId}, matching how commits are abbreviated elsewhere in the UI. */
	public String shortCommitId()
	{
		return commitId == null ? null : commitId.substring(0, Math.min(7, commitId.length()));
	}

	public interface Repo extends PanacheRepository.Managed<Release, UUID>
	{
		// tagName breaks ties so two releases published in the same instant still have a stable order
		@HQL("select r from Release r join fetch r.author where r.repository = :repository order by r.createdAt desc, r.tagName desc")
		List<Release> findByRepository(Repository repository);

		@HQL("select r from Release r join fetch r.author where r.repository = :repository and r.prerelease = false order by r.createdAt desc, r.tagName desc")
		List<Release> findStable(Repository repository);

		@Find
		Optional<Release> findByRepositoryAndTagName(Repository repository, String tagName);

		@HQL("select count(r) from Release r where r.repository = :repository")
		long countByRepository(Repository repository);
	}

}
