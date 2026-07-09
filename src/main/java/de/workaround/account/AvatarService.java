package de.workaround.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Stores user profile pictures on the local filesystem, keyed by user id. Only the content type
 * and update timestamp are persisted on the {@link User} row (see AvatarService callers); the bytes
 * live under {@code gitshark.storage.avatars}, mirroring how bare git repos live under
 * {@code gitshark.storage.root} rather than in the database.
 */
@ApplicationScoped
public class AvatarService
{
	@Inject
	User.Repo users;

	@ConfigProperty(name = "gitshark.storage.avatars")
	Path avatarRoot;

	@Transactional
	public void store(User user, byte[] bytes, String declaredContentType)
	{
		String contentType = ImageValidation.validate(bytes, declaredContentType);

		Path path = avatarPath(user);
		try
		{
			Files.createDirectories(avatarRoot);
			Files.write(path, bytes);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Failed to write avatar to " + path, e);
		}

		User managed = users.findById(user.id);
		managed.avatarContentType = contentType;
		managed.avatarUpdatedAt = Instant.now();
		user.avatarContentType = managed.avatarContentType;
		user.avatarUpdatedAt = managed.avatarUpdatedAt;
	}

	@Transactional
	public void remove(User user)
	{
		try
		{
			Files.deleteIfExists(avatarPath(user));
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Failed to delete avatar for " + user.id, e);
		}

		User managed = users.findById(user.id);
		managed.avatarContentType = null;
		managed.avatarUpdatedAt = null;
		user.avatarContentType = null;
		user.avatarUpdatedAt = null;
	}

	public Optional<byte[]> read(User user)
	{
		Path path = avatarPath(user);
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
			throw new UncheckedIOException("Failed to read avatar for " + user.id, e);
		}
	}

	private Path avatarPath(User user)
	{
		return avatarRoot.resolve(user.id.toString());
	}

}
