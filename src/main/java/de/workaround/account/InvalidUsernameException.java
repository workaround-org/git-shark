package de.workaround.account;

public class InvalidUsernameException extends RuntimeException
{
	public InvalidUsernameException(String message)
	{
		super(message);
	}

}
