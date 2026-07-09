package de.workaround.mirror;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jgit.lib.NullProgressMonitor;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.FetchConnection;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;

import de.workaround.model.PushMirror;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Executes one mirror push over JGit (no shelling out): first the remote ref advertisement is
 * read, then every local ref is force-pushed and every remote ref without a local counterpart is
 * deleted — {@code git push --mirror} semantics. HTTPS authenticates with username/password, SSH
 * with the mirror's generated Ed25519 key; the remote host key is accepted on first contact
 * (returned for pinning) and must match the pinned key afterwards.
 */
@ApplicationScoped
public class MirrorPusher
{
	/** Outcome of one push attempt. {@code seenHostKey} is the server's host key observed during SSH handshake. */
	public record Result(boolean success, String error, String seenHostKey)
	{
	}

	public Result push(Path repositoryPath, PushMirror mirror)
	{
		AtomicReference<String> seenHostKey = new AtomicReference<>();
		SshdSessionFactory sshFactory = null;
		Path sshHome = null;
		try (org.eclipse.jgit.lib.Repository db = new FileRepositoryBuilder()
			.setGitDir(repositoryPath.toFile()).setMustExist(true).build();
			Transport transport = Transport.open(db, new URIish(mirror.remoteUrl)))
		{
			if (mirror.authType == PushMirror.AuthType.HTTPS)
			{
				transport.setCredentialsProvider(
					new UsernamePasswordCredentialsProvider(mirror.username, mirror.secret));
			}
			else
			{
				sshHome = Files.createTempDirectory("gitshark-mirror-ssh");
				sshFactory = sshFactory(sshHome, mirror.secret, mirror.hostKey, seenHostKey);
				((SshTransport) transport).setSshSessionFactory(sshFactory);
			}

			Map<String, RemoteRefUpdate> updates = mirrorUpdates(db, remoteRefNames(transport));
			if (updates.isEmpty())
			{
				return new Result(true, null, seenHostKey.get());
			}
			PushResult result = transport.push(NullProgressMonitor.INSTANCE, updates.values());
			String errors = collectErrors(result);
			return new Result(errors == null, errors, seenHostKey.get());
		}
		catch (Exception e)
		{
			return new Result(false, rootMessage(e), seenHostKey.get());
		}
		finally
		{
			if (sshFactory != null)
			{
				sshFactory.close();
			}
			deleteRecursively(sshHome);
		}
	}

	/** The remote's current ref names, needed to compute deletions before the push. */
	private static Set<String> remoteRefNames(Transport transport) throws Exception
	{
		try (FetchConnection connection = transport.openFetch())
		{
			Set<String> names = new HashSet<>();
			for (Ref ref : connection.getRefs())
			{
				names.add(ref.getName());
			}
			return names;
		}
	}

	private static Map<String, RemoteRefUpdate> mirrorUpdates(org.eclipse.jgit.lib.Repository db,
		Set<String> remoteNames) throws Exception
	{
		Map<String, RemoteRefUpdate> updates = new LinkedHashMap<>();
		List<Ref> localRefs = new ArrayList<>(db.getRefDatabase().getRefsByPrefix("refs/"));
		Set<String> localNames = new HashSet<>();
		for (Ref ref : localRefs)
		{
			if (ref.isSymbolic())
			{
				continue;
			}
			localNames.add(ref.getName());
			updates.put(ref.getName(), new RemoteRefUpdate(db, ref.getName(), ref.getName(), true, null, null));
		}
		for (String remoteName : remoteNames)
		{
			if (remoteName.startsWith("refs/") && !localNames.contains(remoteName))
			{
				// no local counterpart: delete on the remote (source ref null = deletion)
				updates.put(remoteName, new RemoteRefUpdate(db, (String) null, remoteName, true, null, null));
			}
		}
		return updates;
	}

	private static String collectErrors(PushResult result)
	{
		StringBuilder errors = new StringBuilder();
		for (RemoteRefUpdate update : result.getRemoteUpdates())
		{
			switch (update.getStatus())
			{
				case OK, UP_TO_DATE, NON_EXISTING -> {
					// success for this ref
				}
				default -> {
					if (!errors.isEmpty())
					{
						errors.append("; ");
					}
					errors.append(update.getRemoteName()).append(": ").append(update.getStatus());
					if (update.getMessage() != null)
					{
						errors.append(" (").append(update.getMessage()).append(")");
					}
				}
			}
		}
		return errors.isEmpty() ? null : errors.toString();
	}

	private static SshdSessionFactory sshFactory(Path home, String privatePem, String pinnedHostKey,
		AtomicReference<String> seenHostKey) throws Exception
	{
		java.security.KeyPair key = MirrorKeys.fromPrivatePem(privatePem);
		Files.createDirectories(home.resolve(".ssh"));
		return new SshdSessionFactoryBuilder()
			.setPreferredAuthentications("publickey")
			.setHomeDirectory(home.toFile())
			.setSshDirectory(home.resolve(".ssh").toFile())
			.setDefaultKeysProvider(dir -> List.of(key))
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
					// accept-new: pin on first contact, require an exact match afterwards
					String encoded = java.util.Base64.getEncoder().encodeToString(serverKey.getEncoded());
					seenHostKey.set(encoded);
					return pinnedHostKey == null || pinnedHostKey.equals(encoded);
				}
			})
			.build(new JGitKeyCache());
	}

	/** Removes the per-push SSH home again — drain runs forever, leaked temp dirs would grow unbounded. */
	private static void deleteRecursively(Path directory)
	{
		if (directory == null)
		{
			return;
		}
		try
		{
			org.eclipse.jgit.util.FileUtils.delete(directory.toFile(),
				org.eclipse.jgit.util.FileUtils.RECURSIVE | org.eclipse.jgit.util.FileUtils.SKIP_MISSING);
		}
		catch (java.io.IOException e)
		{
			// best-effort cleanup; the next attempt gets a fresh directory either way
		}
	}

	private static String rootMessage(Throwable error)
	{
		Throwable cause = error;
		while (cause.getCause() != null && cause.getCause() != cause)
		{
			cause = cause.getCause();
		}
		String message = cause.getMessage() != null ? cause.getMessage() : error.getMessage();
		return message != null ? message : error.getClass().getSimpleName();
	}

}
