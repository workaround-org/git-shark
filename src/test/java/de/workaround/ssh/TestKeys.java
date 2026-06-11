package de.workaround.ssh;

import java.security.KeyPairGenerator;

import org.apache.sshd.common.config.keys.PublicKeyEntry;

public final class TestKeys
{
	private TestKeys()
	{
	}

	public static String validOpenSshKey()
	{
		try
		{
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return PublicKeyEntry.toString(generator.generateKeyPair().getPublic()) + " test@example.com";
		}
		catch (Exception e)
		{
			throw new IllegalStateException(e);
		}
	}

}
