package de.workaround.ci;

/** A runner referenced a task id ({@code seq}) that does not exist. */
public class TaskNotFoundException extends RuntimeException
{
	public TaskNotFoundException(String message)
	{
		super(message);
	}
}
