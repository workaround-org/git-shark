package de.workaround.git;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import de.workaround.account.ImageValidation;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stores custom repository images on the local filesystem, keyed by repository id. Only the content
 * type and update timestamp are persisted on the {@link Repository} row; the bytes live under
 * {@code gitshark.storage.repo-images}. Mirrors the user-avatar subsystem (AvatarService); a repository
 * with no stored image falls back to its owner's avatar when rendered.
 */
@ApplicationScoped
public class RepositoryImageService
{
	@Inject
	Repository.Repo repositories;

	@Inject
	AccessPolicy accessPolicy;

	@ConfigProperty(name = "gitshark.storage.repo-images")
	Path imageRoot;

	@Transactional
	public void store(User actor, Repository repository, byte[] bytes, String declaredContentType)
	{
		requireWrite(actor, repository);
		String contentType = ImageValidation.validate(bytes, declaredContentType);

		Path path = imagePath(repository);
		try
		{
			Files.createDirectories(imageRoot);
			Files.write(path, bytes);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Failed to write repository image to " + path, e);
		}

		Repository managed = repositories.findById(repository.id);
		managed.imageContentType = contentType;
		managed.imageUpdatedAt = Instant.now();
		repository.imageContentType = managed.imageContentType;
		repository.imageUpdatedAt = managed.imageUpdatedAt;
	}

	@Transactional
	public void remove(User actor, Repository repository)
	{
		requireWrite(actor, repository);
		deleteFile(repository);

		Repository managed = repositories.findById(repository.id);
		managed.imageContentType = null;
		managed.imageUpdatedAt = null;
		repository.imageContentType = null;
		repository.imageUpdatedAt = null;
	}

	/**
	 * Removes only the image file, leaving no DB update. Called when the whole repository is being
	 * deleted (its row and authorization are handled by {@link GitRepositoryService#delete}); without
	 * this the image bytes would be orphaned on disk under a now-unreachable UUID.
	 */
	public void deleteFileFor(Repository repository)
	{
		deleteFile(repository);
	}

	private void deleteFile(Repository repository)
	{
		try
		{
			Files.deleteIfExists(imagePath(repository));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Failed to delete repository image for " + repository.id, e);
		}
	}

	private void requireWrite(User actor, Repository repository)
	{
		if (!accessPolicy.canWrite(actor, repository))
		{
			throw new ForbiddenOperationException("Only the owner may change a repository's image");
		}
	}

	public Optional<byte[]> read(Repository repository)
	{
		Path path = imagePath(repository);
		if (!Files.exists(path))
		{
			return Optional.empty();
		}
		try
		{
			return Optional.of(Files.readAllBytes(path));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Failed to read repository image for " + repository.id, e);
		}
	}

	private Path imagePath(Repository repository)
	{
		return imageRoot.resolve(repository.id.toString());
	}

}
