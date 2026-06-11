package de.workaround.git;

public class InvalidRepositoryNameException extends RuntimeException
{
	public InvalidRepositoryNameException(String name)
	{
		super("Invalid repository name: " + name);
	}

}
