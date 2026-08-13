package de.workaround.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.eclipse.jgit.lib.PersonIdent;

import de.workaround.model.Release;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Publishes and manages a repository's releases. A release annotates exactly one git tag with a title and
 * notes; the tag stays the source of truth for what was released. When the named tag does not exist yet, an
 * annotated tag is cut from the given target (a branch, tag or commit) as part of publishing — the way the
 * "New release" page and Gitea's {@code target_commitish} work.
 *
 * <p>Reading follows the repository's read-visibility rule (enforced by callers); publishing, editing and
 * deleting require write access (owner, collaborator or org member). Deleting a release never deletes its tag.
 */
@ApplicationScoped
public class ReleaseService
{
	@Inject
	Release.Repo releases;

	@Inject
	GitRepositoryService repositories;

	@Inject
	GitTagService tags;

	@Inject
	AccessPolicy accessPolicy;

	@Transactional
	public Release create(User actor, Repository repository, String tagName, String target, String title,
		String body, boolean prerelease)
	{
		requireWrite(actor, repository);
		String tag = tagName == null ? "" : tagName.strip();
		if (tag.isEmpty())
		{
			throw new InvalidReleaseException("Tag name must not be empty");
		}
		if (releases.findByRepositoryAndTagName(repository, tag).isPresent())
		{
			throw new InvalidReleaseException("Tag '" + tag + "' already has a release");
		}
		Path barePath = repositories.repositoryPath(repository);
		String commitId = tags.exists(barePath, tag)
			? tags.resolveCommit(barePath, tag)
				.orElseThrow(() -> new InvalidReleaseException("Tag '" + tag + "' does not point at a commit"))
			: cutTag(actor, barePath, tag, target);
		Release release = new Release();
		release.repository = repository;
		release.author = actor;
		release.tagName = tag;
		release.title = blankTo(title, tag);
		release.body = blankToNull(body);
		release.commitId = commitId;
		release.prerelease = prerelease;
		release.persist();
		return release;
	}

	public List<Release> list(Repository repository)
	{
		return releases.findByRepository(repository);
	}

	public Optional<Release> find(Repository repository, String tagName)
	{
		return releases.findByRepositoryAndTagName(repository, tagName);
	}

	/** The newest release that is not a prerelease — what the list page badges as "Latest". */
	public Optional<Release> findLatest(Repository repository)
	{
		return releases.findStable(repository).stream().findFirst();
	}

	public long count(Repository repository)
	{
		return releases.countByRepository(repository);
	}

	/** Edits title, notes and the prerelease flag; the tag, commit, author and publish time never change. */
	@Transactional
	public void update(User actor, Release release, String title, String body, boolean prerelease)
	{
		requireWrite(actor, release.repository);
		// re-attach: the release may have been loaded in a previous request/transaction, and may have been
		// deleted concurrently since then, so guard against a missing row (findById returns null).
		Release managed = releases.findById(release.id);
		if (managed != null)
		{
			managed.title = blankTo(title, managed.tagName);
			managed.body = blankToNull(body);
			managed.prerelease = prerelease;
		}
	}

	/** Removes the release. The git tag it published is left in place — history is never rewritten here. */
	@Transactional
	public void delete(User actor, Release release)
	{
		requireWrite(actor, release.repository);
		releases.deleteById(release.id);
	}

	private String cutTag(User actor, Path barePath, String tag, String target)
	{
		if (target == null || target.isBlank())
		{
			throw new InvalidReleaseException("Tag '" + tag + "' does not exist — pick a target to create it from");
		}
		String email = actor.email == null || actor.email.isBlank() ? actor.username + "@localhost" : actor.email;
		String name = actor.displayName == null || actor.displayName.isBlank() ? actor.username : actor.displayName;
		return tags.createTag(barePath, tag, target.strip(), new PersonIdent(name, email), "Release " + tag);
	}

	private static String blankTo(String value, String fallback)
	{
		return value == null || value.isBlank() ? fallback : value.strip();
	}

	private static String blankToNull(String value)
	{
		return value == null || value.isBlank() ? null : value.strip();
	}

	private void requireWrite(User actor, Repository repository)
	{
		if (!accessPolicy.canWrite(actor, repository))
		{
			throw new ForbiddenOperationException(
				"Only the repository owner or a collaborator can manage releases");
		}
	}

}
