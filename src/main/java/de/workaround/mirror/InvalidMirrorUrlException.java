package de.workaround.mirror;

/** Thrown when a mirror target URL is malformed or not safe to push to (see {@link MirrorUrlValidator}). */
public class InvalidMirrorUrlException extends RuntimeException
{
	public InvalidMirrorUrlException(String message)
	{
		super(message);
	}

}
