package de.workaround.git;

/** Thrown when an issue is rejected for invalid input, e.g. a blank title. */
public class InvalidIssueException extends RuntimeException
{
	public InvalidIssueException(String message)
	{
		super(message);
	}
}
