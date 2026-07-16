package de.workaround.ci;

/** Thrown when a runner presents a registration token that does not match any stored token. */
public class InvalidRegistrationTokenException extends RuntimeException
{
	public InvalidRegistrationTokenException(String message)
	{
		super(message);
	}
}
