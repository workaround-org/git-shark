package de.workaround.account;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
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
	static final long MAX_BYTES = 2L * 1024 * 1024;

	// Allowed content types mapped to the leading magic bytes we require the upload to actually start
	// with, so a mislabelled or spoofed file is rejected rather than served back with a wrong type.
	private static final Map<String, byte[]> ALLOWED = Map.of(
		"image/png", new byte[] { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A },
		"image/jpeg", new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF },
		"image/gif", new byte[] { 'G', 'I', 'F', '8' },
		"image/webp", new byte[] { 'R', 'I', 'F', 'F' });

	@Inject
	User.Repo users;

	@ConfigProperty(name = "gitshark.storage.avatars")
	Path avatarRoot;

	@Transactional
	public void store(User user, byte[] bytes, String declaredContentType)
	{
		String contentType = validate(bytes, declaredContentType);

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

	private static String validate(byte[] bytes, String declaredContentType)
	{
		if (bytes == null || bytes.length == 0)
		{
			throw new InvalidAvatarException("No image was uploaded.");
		}
		if (bytes.length > MAX_BYTES)
		{
			throw new InvalidAvatarException("Image is too large (max 2 MB).");
		}
		String contentType = declaredContentType == null ? "" : declaredContentType.trim().toLowerCase();
		byte[] magic = ALLOWED.get(contentType);
		if (magic == null)
		{
			throw new InvalidAvatarException("Unsupported image type. Use PNG, JPEG, GIF or WebP.");
		}
		if (!startsWith(bytes, magic))
		{
			throw new InvalidAvatarException("File content does not match its image type.");
		}
		return contentType;
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix)
	{
		if (bytes.length < prefix.length)
		{
			return false;
		}
		for (int i = 0; i < prefix.length; i++)
		{
			if (bytes[i] != prefix[i])
			{
				return false;
			}
		}
		return true;
	}

}
