package de.workaround.ci;

/** Thrown when a post-registration call presents an unknown or mismatched runner uuid/token pair. */
public class RunnerAuthenticationException extends RuntimeException
{
	public RunnerAuthenticationException(String message)
	{
		super(message);
	}
}
