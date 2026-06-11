package de.workaround.account;

public class InvalidSshKeyException extends RuntimeException
{
	public InvalidSshKeyException(String message)
	{
		super(message);
	}

	public InvalidSshKeyException(String message, Throwable cause)
	{
		super(message, cause);
	}

}
