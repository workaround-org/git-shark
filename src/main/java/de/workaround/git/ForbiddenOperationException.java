package de.workaround.git;

public class ForbiddenOperationException extends RuntimeException
{
	public ForbiddenOperationException(String message)
	{
		super(message);
	}

}
