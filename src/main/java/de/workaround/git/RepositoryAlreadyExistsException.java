package de.workaround.git;

public class RepositoryAlreadyExistsException extends RuntimeException
{
	public RepositoryAlreadyExistsException(String owner, String name)
	{
		super("Repository already exists: " + owner + "/" + name);
	}

}
