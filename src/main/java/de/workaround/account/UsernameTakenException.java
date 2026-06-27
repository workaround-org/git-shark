package de.workaround.account;

public class UsernameTakenException extends RuntimeException
{
	public UsernameTakenException(String message)
	{
		super(message);
	}

}
