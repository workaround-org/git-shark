package de.workaround.account;

public class DuplicateSshKeyException extends RuntimeException
{
	public DuplicateSshKeyException(String fingerprint)
	{
		super("SSH key already registered: " + fingerprint);
	}

}
