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

import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Owns the mapping between repository metadata (PostgreSQL) and bare repositories on disk.
 * All transports (HTTP, SSH) and the UI resolve repositories exclusively through this service.
 */
@ApplicationScoped
public class GitRepositoryService
{
	private static final Pattern VALID_NAME = Pattern.compile("[a-zA-Z0-9._-]+");

	@Inject
	Repository.Repo repositories;

	@Inject
	User.Repo users;

	@ConfigProperty(name = "gitshark.storage.root")
	Path storageRoot;

	@Transactional
	public Repository create(User owner, String name, Repository.Visibility visibility, String description)
	{
		validateName(name);
		if (repositories.findByOwnerAndName(owner, name).isPresent())
		{
			throw new RepositoryAlreadyExistsException(owner.username, name);
		}

		Repository repository = new Repository();
		repository.name = name;
		repository.owner = owner;
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
		return users.findByUsername(ownerName)
			.flatMap(owner -> repositories.findByOwnerAndName(owner, stripDotGit(repositoryName)));
	}

	public Path repositoryPath(Repository repository)
	{
		return storageRoot.resolve(repository.owner.id.toString()).resolve(repository.id.toString() + ".git");
	}

	@Transactional
	public void delete(User actor, Repository repository)
	{
		if (actor == null || actor.id == null || !actor.id.equals(repository.owner.id))
		{
			throw new ForbiddenOperationException("Only the owner may delete a repository");
		}
		Path path = repositoryPath(repository);
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
