package de.workaround.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.util.FileUtils;

import de.workaround.model.Organisation;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Owns the mapping between repository metadata (PostgreSQL) and bare repositories on disk.
 * All transports (HTTP, SSH) and the UI resolve repositories exclusively through this service.
 * The {@code {owner}} segment resolves to a user first, then an organisation — the shared handle
 * namespace guarantees the order is only a lookup detail, never an ambiguity.
 */
@ApplicationScoped
public class GitRepositoryService
{
	private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9._-]+");

	@Inject
	Repository.Repo repositories;

	@Inject
	User.Repo users;

	@Inject
	Organisation.Repo organisations;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	RepositoryImageService images;

	@ConfigProperty(name = "gitshark.storage.root")
	Path storageRoot;

	@Transactional
	public Repository create(User owner, String name, Repository.Visibility visibility, String description)
	{
		validateName(name);
		if (repositories.findByOwnerUserAndName(owner, name).isPresent())
		{
			throw new RepositoryAlreadyExistsException(owner.username, name);
		}
		Repository repository = new Repository();
		repository.ownerUser = owner;
		return initialize(repository, name, visibility, description);
	}

	@Transactional
	public Repository create(Organisation owner, String name, Repository.Visibility visibility, String description)
	{
		validateName(name);
		if (repositories.findByOwnerOrgAndName(owner, name).isPresent())
		{
			throw new RepositoryAlreadyExistsException(owner.name, name);
		}
		Repository repository = new Repository();
		repository.ownerOrg = owner;
		return initialize(repository, name, visibility, description);
	}

	/**
	 * Forks {@code source} into {@code actor}'s personal namespace: a new repository carrying the source's
	 * name, visibility and description, its {@code parent} pointing back at the source, with every ref
	 * (branches and tags) copied on disk via a local bare clone. The actor must be able to read the source,
	 * so a private repository can never be forked — and thereby exposed — by someone without read access.
	 */
	@Transactional
	public Repository fork(User actor, Repository source)
	{
		if (actor == null)
		{
			throw new ForbiddenOperationException("Authentication required to fork a repository");
		}
		if (!accessPolicy.canRead(actor, source))
		{
			throw new ForbiddenOperationException("You cannot fork a repository you cannot read");
		}
		if (repositories.findByOwnerUserAndName(actor, source.name).isPresent())
		{
			throw new RepositoryAlreadyExistsException(actor.username, source.name);
		}
		Repository fork = new Repository();
		fork.ownerUser = actor;
		fork.parent = source;
		fork.name = source.name;
		fork.visibility = source.visibility;
		fork.description = source.description;
		fork.persist();

		Path from = repositoryPath(source);
		Path to = repositoryPath(fork);
		try (Git git = Git.cloneRepository().setBare(true).setCloneAllBranches(true)
			.setURI(from.toUri().toString()).setDirectory(to.toFile()).call())
		{
			// clone copies HEAD, all branches and (by default) reachable tags — the full ref set
		}
		catch (GitAPIException e)
		{
			throw new IllegalStateException("Failed to clone " + from + " into fork " + to, e);
		}
		return fork;
	}

	private Repository initialize(Repository repository, String name, Repository.Visibility visibility,
		String description)
	{
		repository.name = name;
		repository.visibility = visibility;
		repository.description = description;
		repository.persist();

		Path path = repositoryPath(repository);
		try (Git git = Git.init().setBare(true).setDirectory(path.toFile()).call())
		{
			git.getRepository().updateRef(Constants.HEAD).link("refs/heads/main");
		}
		catch (GitAPIException | IOException e)
		{
			throw new IllegalStateException("Failed to initialize bare repository at " + path, e);
		}
		return repository;
	}

	public Optional<Repository> find(String ownerName, String repositoryName)
	{
		String name = stripDotGit(repositoryName);
		Optional<Repository> byUser = users.findByUsername(ownerName)
			.flatMap(owner -> repositories.findByOwnerUserAndName(owner, name));
		if (byUser.isPresent())
		{
			return byUser;
		}
		return organisations.findByName(ownerName)
			.flatMap(owner -> repositories.findByOwnerOrgAndName(owner, name));
	}

	public Path repositoryPath(Repository repository)
	{
		return storageRoot.resolve(repository.ownerId().toString()).resolve(repository.id.toString() + ".git");
	}

	@Transactional
	public void changeVisibility(User actor, Repository repository, Repository.Visibility visibility)
	{
		if (!accessPolicy.canAdmin(actor, repository))
		{
			throw new ForbiddenOperationException("Only the owner may change a repository's visibility");
		}
		Repository managed = repositories.findById(repository.id);
		managed.visibility = visibility;
		repository.visibility = visibility;
	}

	@Transactional
	public void delete(User actor, Repository repository)
	{
		if (!accessPolicy.canAdmin(actor, repository))
		{
			throw new ForbiddenOperationException("Only the owner may delete a repository");
		}
		Path path = repositoryPath(repository);
		images.deleteFileFor(repository);
		repositories.deleteById(repository.id);
		try
		{
			FileUtils.delete(path.toFile(), FileUtils.RECURSIVE | FileUtils.SKIP_MISSING);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Failed to delete repository directory " + path, e);
		}
	}

	public List<Repository> listVisibleTo(User user)
	{
		return user == null ? repositories.findPublic() : repositories.findVisibleTo(user);
	}

	public List<Repository> listOwnedBy(Organisation organisation)
	{
		return repositories.findByOwnerOrg(organisation);
	}

	private static void validateName(String name)
	{
		if (name == null || name.isEmpty() || name.equals(".") || name.equals("..")
			|| !VALID_NAME.matcher(name).matches())
		{
			throw new InvalidRepositoryNameException(name);
		}
	}

	private static String stripDotGit(String name)
	{
		return name != null && name.endsWith(".git") ? name.substring(0, name.length() - 4) : name;
	}

}
