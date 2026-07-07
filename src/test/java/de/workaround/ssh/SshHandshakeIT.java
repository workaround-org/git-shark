package de.workaround.ssh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusIntegrationTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives a complete SSH handshake (key exchange, host key signature, cipher negotiation)
 * against the packaged application and expects a clean publickey-auth rejection for an
 * unregistered key. KEX and signatures are the classic native-image failure zone for
 * Apache SSHD + BouncyCastle, so a crypto regression fails this test with a negotiation
 * error instead of the expected auth failure.
 */
@QuarkusIntegrationTest
class SshHandshakeIT
{
	@Test
	void fullHandshakeCompletesAndUnregisteredKeyIsRejectedAtAuth() throws Exception
	{
		int port = Integer.getInteger("gitshark.ssh.port", 2222);
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair unregistered = generator.generateKeyPair();

		Path home = Files.createTempDirectory("it-ssh-home");
		Files.createDirectories(home.resolve(".ssh"));
		try (SshdSessionFactory factory = new SshdSessionFactoryBuilder()
			.setPreferredAuthentications("publickey")
			.setHomeDirectory(home.toFile())
			.setSshDirectory(home.resolve(".ssh").toFile())
			.setDefaultKeysProvider(dir -> List.of(unregistered))
			.setServerKeyDatabase((ignoredHome, ignoredSsh) -> new ServerKeyDatabase()
			{
				@Override
				public List<java.security.PublicKey> lookup(String connectAddress,
					java.net.InetSocketAddress remoteAddress, Configuration config)
				{
					return List.of();
				}

				@Override
				public boolean accept(String connectAddress, java.net.InetSocketAddress remoteAddress,
					java.security.PublicKey serverKey, Configuration config,
					org.eclipse.jgit.transport.CredentialsProvider provider)
				{
					return true;
				}
			})
			.build(new JGitKeyCache()))
		{
			Path target = Files.createTempDirectory("it-ssh-clone");
			TransportException e = assertThrows(TransportException.class, () -> Git.cloneRepository()
				.setURI("ssh://git@localhost:" + port + "/alice/demo.git")
				.setDirectory(target.toFile())
				.setTransportConfigCallback(t -> ((SshTransport) t).setSshSessionFactory(factory))
				.call());

			assertNotNull(e.getMessage());
			String message = e.getMessage().toLowerCase();
			assertTrue(message.contains("auth") || message.contains("log in") || message.contains("refused"),
				"handshake must reach the auth stage (crypto negotiation intact), but was: " + e.getMessage());
		}
	}

}
