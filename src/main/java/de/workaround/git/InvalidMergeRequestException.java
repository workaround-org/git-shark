package de.workaround.git;

/** Thrown when a merge request is rejected for invalid input or state, e.g. a blank title or a merge conflict. */
public class InvalidMergeRequestException extends RuntimeException
{
	public InvalidMergeRequestException(String message)
	{
		super(message);
	}
}
