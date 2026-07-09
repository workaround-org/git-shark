package de.workaround.mirror;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.http.AccessTokenService;
import de.workaround.http.GitSmartHttpTest;
import de.workaround.model.MirrorSync;
import de.workaround.model.PushMirror;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end HTTPS mirroring against a second local repository served over smart HTTP: a push to
 * the source repository enqueues an async sync, the drain replicates all refs (including
 * deletions, i.e. {@code git push --mirror} semantics), failures retry with backoff and
 * dead-letter after the budget, and rapid pushes coalesce into one pending sync.
 */
@QuarkusTest
class MirrorPushTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	MirrorService mirrorService;

	@Inject
	AccessTokenService tokenService;

	@Inject
	MirrorSync.Repo syncs;

	@Inject
	PushMirror.Repo mirrors;

	@ConfigProperty(name = "gitshark.mirror.max-attempts")
	int maxAttempts;

	@TestHTTPResource("/git")
	URL gitBase;

	@Test
	void manualSyncReplicatesAllRefsIncludingDeletions() throws Exception
	{
		User alice = persistUser();
		User bob = persistUser();
		Repository src = service.create(alice, "mirror-src", Repository.Visibility.PUBLIC, null);
		Repository dst = service.create(bob, "mirror-dst", Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(src));

		PushMirror mirror = createHttpMirror(alice, src, dst, token(bob));

		mirrorService.pushNow(alice, src, mirror.id);
		mirrorService.drainOnce();
		assertEquals(ref(src, "refs/heads/main"), ref(dst, "refs/heads/main"), "main must be replicated");

		// grow: a new branch on the source appears on the target
		pushBranch(src, "feature");
		mirrorService.pushNow(alice, src, mirror.id);
		mirrorService.drainOnce();
		assertNotNull(ref(dst, "refs/heads/feature"), "new branch must be replicated");

		// shrink: deleting the branch on the source deletes it on the target (--mirror semantics)
		deleteBranch(src, "feature");
		mirrorService.pushNow(alice, src, mirror.id);
		mirrorService.drainOnce();
		assertNull(ref(dst, "refs/heads/feature"), "deleted branch must be removed from the target");

		PushMirror after = reload(mirror.id);
		assertNotNull(after.lastSuccessAt, "successful sync must be recorded");
		assertNull(after.lastError, "successful sync must clear the error");
	}

	@Test
	void incomingHttpPushEnqueuesAndReplicates() throws Exception
	{
		User alice = persistUser();
		User bob = persistUser();
		Repository src = service.create(alice, "mirror-hook-src", Repository.Visibility.PUBLIC, null);
		Repository dst = service.create(bob, "mirror-hook-dst", Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(src));
		PushMirror mirror = createHttpMirror(alice, src, dst, token(bob));

		// a real push over smart HTTP must enqueue the sync via the post-receive hook
		String aliceToken = token(alice);
		Path work = Files.createTempDirectory("mirror-hook");
		try (Git git = Git.cloneRepository()
			.setURI(gitBase + "/" + alice.username + "/" + src.name + ".git")
			.setDirectory(work.toFile()).call())
		{
			Files.writeString(work.resolve("update.txt"), "mirror me\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("update").setSign(false)
				.setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
			git.push()
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(alice.username, aliceToken))
				.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
				.call();
		}

		assertTrue(pendingSyncCount(mirror.id) >= 1, "push must enqueue a mirror sync");
		mirrorService.drainOnce();
		assertEquals(ref(src, "refs/heads/main"), ref(dst, "refs/heads/main"));
	}

	@Test
	void rapidSyncRequestsCoalesceIntoOnePending() throws Exception
	{
		User alice = persistUser();
		Repository src = service.create(alice, "mirror-coalesce", Repository.Visibility.PUBLIC, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(src));
		PushMirror mirror = mirrorService.create(alice, src, "https://mirror-target.example/x/y.git",
			PushMirror.AuthType.HTTPS, "deploy", "token");

		mirrorService.pushNow(alice, src, mirror.id);
		mirrorService.pushNow(alice, src, mirror.id);

		assertEquals(1, pendingSyncCount(mirror.id), "pending syncs must coalesce");
	}

	@Test
	void failingMirrorRetriesThenDeadLetters() throws Exception
	{
		User alice = persistUser();
		User bob = persistUser();
		Repository src = service.create(alice, "mirror-fail-src", Repository.Visibility.PUBLIC, null);
		Repository dst = service.create(bob, "mirror-fail-dst", Repository.Visibility.PRIVATE, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(src));
		// wrong credentials: the target rejects every push attempt
		PushMirror mirror = createHttpMirror(alice, src, dst, "not-a-valid-token");

		mirrorService.pushNow(alice, src, mirror.id);
		UUID syncId = pendingSyncId(mirror.id);
		for (int i = 0; i < maxAttempts; i++)
		{
			mirrorService.attempt(syncId);
		}

		MirrorSync sync = reloadSync(syncId);
		assertEquals(MirrorSync.State.FAILED, sync.state, "exhausted sync must be dead-lettered");
		assertEquals(maxAttempts, sync.attempts);
		PushMirror after = reload(mirror.id);
		assertNotNull(after.lastError, "last error must be visible on the mirror");
		assertNotNull(after.lastAttemptAt);
		assertNull(after.lastSuccessAt);
	}

	private PushMirror createHttpMirror(User actor, Repository src, Repository dst, String dstToken)
	{
		String url = gitBase + "/" + dst.owner.username + "/" + dst.name + ".git";
		return mirrorService.create(actor, src, url, PushMirror.AuthType.HTTPS, dst.owner.username, dstToken);
	}

	private void pushBranch(Repository src, String branch) throws Exception
	{
		Path work = Files.createTempDirectory("mirror-branch");
		try (Git git = Git.cloneRepository()
			.setURI(service.repositoryPath(src).toUri().toString())
			.setDirectory(work.toFile()).call())
		{
			Files.writeString(work.resolve(branch + ".txt"), branch + "\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("add " + branch).setSign(false)
				.setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
			git.push().setRefSpecs(new RefSpec("HEAD:refs/heads/" + branch)).call();
		}
	}

	private void deleteBranch(Repository src, String branch) throws Exception
	{
		Path work = Files.createTempDirectory("mirror-del");
		try (Git git = Git.cloneRepository()
			.setURI(service.repositoryPath(src).toUri().toString())
			.setDirectory(work.toFile()).call())
		{
			git.push().setRefSpecs(new RefSpec(":refs/heads/" + branch)).call();
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
	String token(User user)
	{
		return tokenService.create(user, "mirror-test").plaintext();
	}

	@Transactional
	long pendingSyncCount(UUID mirrorId)
	{
		return syncs.findByMirrorId(mirrorId).stream()
			.filter(s -> s.state == MirrorSync.State.PENDING).count();
	}

	@Transactional
	UUID pendingSyncId(UUID mirrorId)
	{
		return syncs.findByMirrorId(mirrorId).stream()
			.filter(s -> s.state == MirrorSync.State.PENDING)
			.findFirst().orElseThrow().id;
	}

	@Transactional
	MirrorSync reloadSync(UUID id)
	{
		return syncs.findById(id);
	}

	@Transactional
	PushMirror reload(UUID id)
	{
		return mirrors.findById(id);
	}

	@Transactional
	User persistUser()
	{
		String name = "mirror-" + UUID.randomUUID().toString().substring(0, 8);
		User user = new User();
		user.oidcSub = "sub-" + name;
		user.username = name;
		user.persist();
		return user;
	}

}
