package de.workaround.git;

/** Thrown when a release is rejected for invalid input, e.g. a blank tag name or an unknown target. */
public class InvalidReleaseException extends RuntimeException
{
	public InvalidReleaseException(String message)
	{
		super(message);
	}
}
