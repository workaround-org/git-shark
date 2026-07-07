package de.workaround.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.command.CommandFactory;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.UploadPack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Accepts only git-upload-pack and git-receive-pack; everything else (shells, other commands)
 * is rejected. Repository resolution and authorization run through the shared services.
 */
@ApplicationScoped
public class GitSshCommandFactory implements CommandFactory
{
	@Inject
	SshGitBridge bridge;

	@Inject
	de.workaround.federation.FederationPushService pushService;

	@Inject
	de.workaround.git.IssueCommitCloser issueCloser;

	@Override
	public Command createCommand(ChannelSession channel, String command)
	{
		return new GitPackCommand(command);
	}

	private final class GitPackCommand implements Command, Runnable
	{
		private final String commandLine;

		private InputStream in;

		private OutputStream out;

		private OutputStream err;

		private ExitCallback exit;

		private ChannelSession channel;

		private Thread worker;

		private GitPackCommand(String commandLine)
		{
			this.commandLine = commandLine;
		}

		@Override
		public void setInputStream(InputStream in)
		{
			this.in = in;
		}

		@Override
		public void setOutputStream(OutputStream out)
		{
			this.out = out;
		}

		@Override
		public void setErrorStream(OutputStream err)
		{
			this.err = err;
		}

		@Override
		public void setExitCallback(ExitCallback callback)
		{
			this.exit = callback;
		}

		@Override
		public void start(ChannelSession channel, Environment env) throws IOException
		{
			this.channel = channel;
			worker = new Thread(this, "git-ssh-" + channel.getServerSession().getIoSession().getRemoteAddress());
			worker.start();
		}

		@Override
		public void destroy(ChannelSession channel)
		{
			if (worker != null)
			{
				worker.interrupt();
			}
		}

		@Override
		public void run()
		{
			try
			{
				boolean receive;
				String rawPath;
				if (commandLine.startsWith("git-upload-pack "))
				{
					receive = false;
					rawPath = argument(commandLine);
				}
				else if (commandLine.startsWith("git-receive-pack "))
				{
					receive = true;
					rawPath = argument(commandLine);
				}
				else
				{
					fail("Only git-upload-pack and git-receive-pack are supported");
					return;
				}

				UUID userId = channel.getServerSession().getAttribute(GitSshAuthenticator.USER_ID);
				Optional<Path> repoPath = bridge.resolveAuthorized(userId, rawPath, receive);
				if (repoPath.isEmpty())
				{
					fail("Repository not found or access denied: " + rawPath);
					return;
				}

				try (var db = new FileRepositoryBuilder().setGitDir(repoPath.get().toFile()).setMustExist(true).build())
				{
					if (receive)
					{
						ReceivePack receivePack = new ReceivePack(db);
						String[] parts = rawPath.split("/");
						if (parts.length == 2)
						{
							String ownerName = parts[0];
							String repoName = parts[1];
							receivePack.setPostReceiveHook((rp, commands) -> {
								pushService.onPush(ownerName, repoName, userId, rp.getRepository(), commands);
								issueCloser.onPush(ownerName, repoName, userId, rp.getRepository(), commands);
							});
						}
						receivePack.receive(in, out, err);
					}
					else
					{
						new UploadPack(db).upload(in, out, err);
					}
				}
				exit.onExit(0);
			}
			catch (Exception e)
			{
				try
				{
					fail("git-shark: " + e.getMessage());
				}
				catch (IOException ignored)
				{
					exit.onExit(1);
				}
			}
		}

		private void fail(String message) throws IOException
		{
			err.write((message + "\n").getBytes(StandardCharsets.UTF_8));
			err.flush();
			exit.onExit(1);
		}
	}

	private static String argument(String commandLine)
	{
		String arg = commandLine.substring(commandLine.indexOf(' ') + 1).trim();
		if (arg.startsWith("'") && arg.endsWith("'") && arg.length() >= 2)
		{
			arg = arg.substring(1, arg.length() - 1);
		}
		return arg.startsWith("/") ? arg.substring(1) : arg;
	}

}
