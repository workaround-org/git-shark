package de.workaround.ssh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Embedded SSH server for Git transport. Listens on a dedicated port (default 2222), uses
 * public-key authentication only (no passwords, no shell), and persists its host key on the
 * data volume so the host identity is stable across restarts.
 */
@ApplicationScoped
public class GitSshServer
{
	@ConfigProperty(name = "gitshark.ssh.port")
	int port;

	@ConfigProperty(name = "gitshark.ssh.host-key-path")
	Path hostKeyPath;

	@Inject
	GitSshAuthenticator authenticator;

	@Inject
	GitSshCommandFactory commandFactory;

	private SshServer sshd;

	void onStart(@Observes StartupEvent event) throws IOException
	{
		if (hostKeyPath.getParent() != null)
		{
			Files.createDirectories(hostKeyPath.getParent());
		}
		sshd = SshServer.setUpDefaultServer();
		sshd.setPort(port);
		sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKeyPath));
		sshd.setPublickeyAuthenticator(authenticator);
		sshd.setCommandFactory(commandFactory);
		sshd.start();
	}

	void onStop(@Observes ShutdownEvent event) throws IOException
	{
		if (sshd != null)
		{
			sshd.stop();
		}
	}

	public int actualPort()
	{
		return sshd.getPort();
	}

}
