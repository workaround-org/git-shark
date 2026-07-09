package de.workaround.account;

/**
 * Thrown when an uploaded image (a user avatar or a repository image) fails validation: empty,
 * too large, an unsupported content type, or content whose magic bytes do not match its declared type.
 */
public class InvalidImageException extends RuntimeException
{
	public InvalidImageException(String message)
	{
		super(message);
	}

}
