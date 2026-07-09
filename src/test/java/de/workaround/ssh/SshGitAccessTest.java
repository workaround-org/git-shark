package de.workaround.ssh;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.List;
import java.util.UUID;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.sshd.JGitKeyCache;
import org.eclipse.jgit.transport.sshd.ServerKeyDatabase;
import org.eclipse.jgit.transport.sshd.SshdSessionFactory;
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.http.GitSmartHttpTest;
import de.workaround.model.Repository;
import de.workaround.model.SshKey;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SshGitAccessTest
{
	private static SshdSessionFactory factory;

	@Inject
	GitRepositoryService service;

	@Inject
	GitSshServer sshServer;

	@ConfigProperty(name = "gitshark.ssh.host-key-path")
	Path hostKeyPath;

	@AfterAll
	static void closeFactory()
	{
		if (factory != null)
		{
			factory.close();
		}
	}

	@Test
	void clonesOverSshWithRegisteredKey() throws Exception
	{
		KeyPair key = generateKeyPair();
		User owner = persistUserWithKey(key);
		Repository repo = service.create(owner, "ssh-clone", Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));

		Path target = Files.createTempDirectory("ssh-clone");
		try (Git clone = cloneOverSsh(sshUrl(owner, "ssh-clone"), target, key))
		{
			assertTrue(Files.exists(target.resolve("README.md")));
		}
	}

	@Test
	void rejectsUnknownKey() throws Exception
	{
		KeyPair registered = generateKeyPair();
		User owner = persistUserWithKey(registered);
		Repository repo = service.create(owner, "ssh-unknown-key", Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));

		KeyPair unregistered = generateKeyPair();
		Path target = Files.createTempDirectory("ssh-unknown");
		assertThrows(TransportException.class,
			() -> cloneOverSsh(sshUrl(owner, "ssh-unknown-key"), target, unregistered));
	}

	@Test
	void pushesOverSshAsOwner() throws Exception
	{
		KeyPair key = generateKeyPair();
		User owner = persistUserWithKey(key);
		Repository repo = service.create(owner, "ssh-push", Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));
		ObjectId before = mainRef(service.repositoryPath(repo));

		Path work = Files.createTempDirectory("ssh-push");
		try (Git git = cloneOverSsh(sshUrl(owner, "ssh-push"), work, key))
		{
			Files.writeString(work.resolve("new.txt"), "via ssh\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("ssh push").setSign(false)
				.setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
			git.push()
				.setTransportConfigCallback(SshGitAccessTest::configureTransport)
				.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
				.call();
		}

		assertNotEquals(before, mainRef(service.repositoryPath(repo)), "push must advance main");
	}

	@Test
	void deniesPrivateRepositoryReadForNonOwner() throws Exception
	{
		KeyPair ownerKey = generateKeyPair();
		User owner = persistUserWithKey(ownerKey);
		KeyPair strangerKey = generateKeyPair();
		persistUserWithKey(strangerKey);
		Repository repo = service.create(owner, "ssh-private", Repository.Visibility.PRIVATE, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));

		Path target = Files.createTempDirectory("ssh-private");
		// JGit wraps the server-side denial in InvalidRemoteException (repo "not found")
		assertThrows(org.eclipse.jgit.api.errors.GitAPIException.class,
			() -> cloneOverSsh(sshUrl(owner, "ssh-private"), target, strangerKey));
	}

	@Test
	void deniesReceivePackWithoutWritePermission() throws Exception
	{
		KeyPair ownerKey = generateKeyPair();
		User owner = persistUserWithKey(ownerKey);
		KeyPair strangerKey = generateKeyPair();
		persistUserWithKey(strangerKey);
		Repository repo = service.create(owner, "ssh-guarded", Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));
		ObjectId before = mainRef(service.repositoryPath(repo));

		Path work = Files.createTempDirectory("ssh-guarded");
		try (Git git = cloneOverSsh(sshUrl(owner, "ssh-guarded"), work, strangerKey))
		{
			Files.writeString(work.resolve("intrusion.txt"), "nope\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("intrusion").setSign(false)
				.setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
			assertThrows(TransportException.class, () -> git.push()
				.setTransportConfigCallback(SshGitAccessTest::configureTransport)
				.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
				.call());
		}

		assertEquals(before, mainRef(service.repositoryPath(repo)), "refs must not change on denied push");
	}

	@Inject
	de.workaround.git.CollaboratorService collaboratorService;

	@Test
	void collaboratorClonesAndPushesPrivateRepositoryOverSsh() throws Exception
	{
		KeyPair ownerKey = generateKeyPair();
		User owner = persistUserWithKey(ownerKey);
		KeyPair collaboratorKey = generateKeyPair();
		User collaborator = persistUserWithKey(collaboratorKey);
		Repository repo = service.create(owner, "ssh-collab", Repository.Visibility.PRIVATE, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));
		addCollaborator(repo, collaborator);
		ObjectId before = mainRef(service.repositoryPath(repo));

		Path work = Files.createTempDirectory("ssh-collab");
		try (Git git = cloneOverSsh(sshUrl(owner, "ssh-collab"), work, collaboratorKey))
		{
			Files.writeString(work.resolve("collab.txt"), "via collaborator\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("collaborator push").setSign(false)
				.setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
			git.push()
				.setTransportConfigCallback(SshGitAccessTest::configureTransport)
				.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
				.call();
		}

		assertNotEquals(before, mainRef(service.repositoryPath(repo)), "collaborator push must advance main");
	}

	@Transactional
	void addCollaborator(Repository repo, User user)
	{
		collaboratorService.add(repo.owner, repo, user.username);
	}

	@Test
	void hostKeyIsPersistedAndStable() throws Exception
	{
		assertTrue(Files.exists(hostKeyPath), "host key file must exist after server start");

		SimpleGeneratorHostKeyProvider reloaded = new SimpleGeneratorHostKeyProvider(hostKeyPath);
		List<KeyPair> first = (List<KeyPair>) loadKeys(reloaded);
		SimpleGeneratorHostKeyProvider again = new SimpleGeneratorHostKeyProvider(hostKeyPath);
		List<KeyPair> second = (List<KeyPair>) loadKeys(again);

		assertEquals(KeyUtils.getFingerPrint(BuiltinDigests.sha256, first.get(0).getPublic()),
			KeyUtils.getFingerPrint(BuiltinDigests.sha256, second.get(0).getPublic()),
			"host key fingerprint must be stable across provider reloads");
	}

	private static Iterable<KeyPair> loadKeys(KeyPairProvider provider) throws Exception
	{
		java.util.ArrayList<KeyPair> keys = new java.util.ArrayList<>();
		provider.loadKeys(null).forEach(keys::add);
		return keys;
	}

	private String sshUrl(User owner, String repoName)
	{
		return "ssh://git@localhost:" + sshServer.actualPort() + "/" + owner.username + "/" + repoName + ".git";
	}

	private static Git cloneOverSsh(String url, Path target, KeyPair key) throws Exception
	{
		installFactory(key);
		return Git.cloneRepository()
			.setURI(url)
			.setDirectory(target.toFile())
			.setTransportConfigCallback(SshGitAccessTest::configureTransport)
			.call();
	}

	private static void configureTransport(org.eclipse.jgit.transport.Transport transport)
	{
		((SshTransport) transport).setSshSessionFactory(factory);
	}

	private static synchronized void installFactory(KeyPair key) throws Exception
	{
		if (factory != null)
		{
			factory.close();
		}
		Path home = Files.createTempDirectory("ssh-home");
		Files.createDirectories(home.resolve(".ssh"));
		factory = new SshdSessionFactoryBuilder()
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
					java.security.PublicKey serverKey, Configuration config, org.eclipse.jgit.transport.CredentialsProvider provider)
				{
					return true;
				}
			})
			.build(new JGitKeyCache());
	}

	private static KeyPair generateKeyPair() throws Exception
	{
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		return generator.generateKeyPair();
	}

	@Transactional
	User persistUserWithKey(KeyPair key) throws Exception
	{
		String name = "ssh-" + UUID.randomUUID();
		User user = new User();
		user.oidcSub = "sub-" + name;
		user.username = name;
		user.persist();

		SshKey sshKey = new SshKey();
		sshKey.user = user;
		sshKey.title = "test-key";
		sshKey.publicKey = PublicKeyEntry.toString(key.getPublic());
		sshKey.fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key.getPublic());
		sshKey.persist();
		return user;
	}

	private static ObjectId mainRef(Path barePath) throws Exception
	{
		try (var repo = new FileRepositoryBuilder().setGitDir(barePath.toFile()).setMustExist(true).build())
		{
			return repo.resolve("refs/heads/main");
		}
	}

}
