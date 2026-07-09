package de.workaround.mirror;

import java.util.UUID;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Test;

import de.workaround.account.SshKeyService;
import de.workaround.git.GitRepositoryService;
import de.workaround.http.GitSmartHttpTest;
import de.workaround.model.PushMirror;
import de.workaround.model.Repository;
import de.workaround.model.User;
import de.workaround.ssh.GitSshServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * End-to-end SSH mirroring against this instance's own embedded SSH server: the mirror's generated
 * Ed25519 public key is registered as the target owner's key (the "deploy key" step), the sync
 * pushes over SSH with the server-held private key, and the first contact pins the host key.
 */
@QuarkusTest
class MirrorSshPushTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	MirrorService mirrorService;

	@Inject
	SshKeyService sshKeys;

	@Inject
	PushMirror.Repo mirrors;

	@Inject
	GitSshServer sshServer;

	@Test
	void sshMirrorReplicatesAfterDeployKeyIsAuthorized() throws Exception
	{
		long tempDirsBefore = sshTempDirCount();
		User alice = persistUser();
		User bob = persistUser();
		Repository src = service.create(alice, "ssh-mirror-src", Repository.Visibility.PUBLIC, null);
		Repository dst = service.create(bob, "ssh-mirror-dst", Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(src));

		String url = "ssh://git@localhost:" + sshServer.actualPort() + "/" + bob.username + "/" + dst.name + ".git";
		PushMirror mirror = mirrorService.create(alice, src, url, PushMirror.AuthType.SSH, null, null);
		assertNotNull(mirror.publicKey, "SSH mirror must generate a deploy key");
		assertNull(mirror.hostKey, "host key is only pinned on first contact");

		// the owner authorizes the deploy key at the target (here: the same instance, as bob)
		sshKeys.add(bob, "mirror-deploy-key", mirror.publicKey);

		mirrorService.pushNow(alice, src, mirror.id);
		mirrorService.drainOnce();

		assertEquals(ref(src, "refs/heads/main"), ref(dst, "refs/heads/main"), "main must be replicated over SSH");
		PushMirror after = reload(mirror.id);
		assertNotNull(after.lastSuccessAt);
		assertNotNull(after.hostKey, "first successful contact must pin the server host key");
		assertEquals(tempDirsBefore, sshTempDirCount(), "push attempts must not leak SSH temp directories");
	}

	@Test
	void sshMirrorFailsWithoutAuthorizedDeployKey() throws Exception
	{
		long tempDirsBefore = sshTempDirCount();
		User alice = persistUser();
		User bob = persistUser();
		Repository src = service.create(alice, "ssh-mirror-unauth-src", Repository.Visibility.PUBLIC, null);
		Repository dst = service.create(bob, "ssh-mirror-unauth-dst", Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(src));

		String url = "ssh://git@localhost:" + sshServer.actualPort() + "/" + bob.username + "/" + dst.name + ".git";
		PushMirror mirror = mirrorService.create(alice, src, url, PushMirror.AuthType.SSH, null, null);
		// deploy key intentionally NOT registered at the target

		mirrorService.pushNow(alice, src, mirror.id);
		mirrorService.drainOnce();

		assertNull(ref(dst, "refs/heads/main"), "target must stay untouched");
		assertNotNull(reload(mirror.id).lastError, "auth failure must be recorded on the mirror");
		assertEquals(tempDirsBefore, sshTempDirCount(), "failed attempts must not leak SSH temp directories");
	}

	/** Directories MirrorPusher creates for per-push SSH homes — must be cleaned up after every attempt. */
	private static long sshTempDirCount() throws Exception
	{
		java.nio.file.Path tmp = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"));
		try (var entries = java.nio.file.Files.list(tmp))
		{
			return entries.filter(p -> p.getFileName().toString().startsWith("gitshark-mirror-ssh")).count();
		}
	}

	private ObjectId ref(Repository repo, String name) throws Exception
	{
		try (var db = new FileRepositoryBuilder()
			.setGitDir(service.repositoryPath(repo).toFile()).setMustExist(true).build())
		{
			return db.resolve(name);
		}
	}

	@Transactional
	PushMirror reload(UUID id)
	{
		return mirrors.findById(id);
	}

	@Transactional
	User persistUser()
	{
		String name = "sshm-" + UUID.randomUUID().toString().substring(0, 8);
		User user = new User();
		user.oidcSub = "sub-" + name;
		user.username = name;
		user.persist();
		return user;
	}

}
