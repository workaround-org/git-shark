package de.workaround.git;

/**
 * Rejected collaborator mutation: the username does not resolve to a local user, the user already
 * is a collaborator, or the user is the repository owner. Carries the user-facing message shown on
 * the collaborators settings page.
 */
public class InvalidCollaboratorException extends RuntimeException
{
	public InvalidCollaboratorException(String message)
	{
		super(message);
	}
}
