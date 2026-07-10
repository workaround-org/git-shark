package de.workaround.account;

/** Rejected organisation input: invalid or taken name, unknown member, duplicate member, last-owner violations. */
public class InvalidOrganisationException extends RuntimeException
{
	public InvalidOrganisationException(String message)
	{
		super(message);
	}
}
