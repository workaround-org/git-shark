package de.workaround.http;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
public class GitSmartHttpTest
{
	@Inject
	GitRepositoryService service;

	@TestHTTPResource("/git")
	URL gitBase;

	@Test
	void clonesPublicRepositoryAnonymously() throws Exception
	{
		User owner = persistUser();
		Repository repo = createRepo(owner, "public-clone", Repository.Visibility.PUBLIC);
		seedCommit(service.repositoryPath(repo));

		Path target = Files.createTempDirectory("clone-target");
		try (Git clone = Git.cloneRepository()
			.setURI(gitBase + "/" + owner.username + "/public-clone.git")
			.setDirectory(target.toFile())
			.call())
		{
			assertTrue(Files.exists(target.resolve("README.md")), "cloned working tree must contain seeded file");
		}
	}

	@Inject
	AccessTokenService tokenService;

	@Test
	void anonymousPushIsRejectedAndRefsUnchanged() throws Exception
	{
		User owner = persistUser();
		Repository repo = createRepo(owner, "anon-push", Repository.Visibility.PUBLIC);
		seedCommit(service.repositoryPath(repo));
		ObjectId before = mainRef(service.repositoryPath(repo));

		Path work = Files.createTempDirectory("anon-push");
		try (Git git = cloneOver(httpUrl(owner, "anon-push"), work, null))
		{
			commitFile(git, work, "evil.txt");
			assertThrows(TransportException.class,
				() -> git.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call());
		}

		assertEquals(before, mainRef(service.repositoryPath(repo)), "refs must not change on rejected push");
	}

	@Test
	void ownerPushesWithAccessToken() throws Exception
	{
		User owner = persistUser();
		Repository repo = createRepo(owner, "token-push", Repository.Visibility.PUBLIC);
		seedCommit(service.repositoryPath(repo));
		String token = createToken(owner);

		Path work = Files.createTempDirectory("token-push");
		try (Git git = cloneOver(httpUrl(owner, "token-push"), work, null))
		{
			commitFile(git, work, "feature.txt");
			git.push()
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(owner.username, token))
				.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
				.call();
		}

		ObjectId after = mainRef(service.repositoryPath(repo));
		assertTrue(after != null, "main must exist after push");
	}

	@Test
	void pushWithoutWritePermissionIsRejected() throws Exception
	{
		User owner = persistUser();
		User stranger = persistUser();
		Repository repo = createRepo(owner, "guarded-push", Repository.Visibility.PUBLIC);
		seedCommit(service.repositoryPath(repo));
		ObjectId before = mainRef(service.repositoryPath(repo));
		String strangerToken = createToken(stranger);

		Path work = Files.createTempDirectory("stranger-push");
		try (Git git = cloneOver(httpUrl(owner, "guarded-push"), work, null))
		{
			commitFile(git, work, "intrusion.txt");
			assertThrows(TransportException.class, () -> git.push()
				.setCredentialsProvider(new UsernamePasswordCredentialsProvider(stranger.username, strangerToken))
				.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
				.call());
		}

		assertEquals(before, mainRef(service.repositoryPath(repo)), "refs must not change on forbidden push");
	}

	@Inject
	de.workaround.git.CollaboratorService collaboratorService;

	@Test
	void collaboratorClonesAndPushesPrivateRepository() throws Exception
	{
		User owner = persistUser();
		User collaborator = persistUser();
		Repository repo = createRepo(owner, "collab-push", Repository.Visibility.PRIVATE);
		seedCommit(service.repositoryPath(repo));
		addCollaborator(repo, collaborator);
		ObjectId before = mainRef(service.repositoryPath(repo));
		String token = createToken(collaborator);
		var credentials = new UsernamePasswordCredentialsProvider(collaborator.username, token);

		Path work = Files.createTempDirectory("collab-push");
		try (Git git = cloneOver(httpUrl(owner, "collab-push"), work, credentials))
		{
			commitFile(git, work, "collab.txt");
			git.push()
				.setCredentialsProvider(credentials)
				.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
				.call();
		}

		assertNotEquals(before, mainRef(service.repositoryPath(repo)), "collaborator push must advance main");
	}

	@Transactional
	void addCollaborator(Repository repo, User user)
	{
		collaboratorService.add(repo.ownerUser, repo, user.username);
	}

	@Test
	void privateRepositoryRequiresAuthenticationForClone() throws Exception
	{
		User owner = persistUser();
		Repository repo = createRepo(owner, "private-clone", Repository.Visibility.PRIVATE);
		seedCommit(service.repositoryPath(repo));

		Path anonTarget = Files.createTempDirectory("private-anon");
		assertThrows(TransportException.class,
			() -> Git.cloneRepository().setURI(httpUrl(owner, "private-clone")).setDirectory(anonTarget.toFile()).call());

		String token = createToken(owner);
		Path authTarget = Files.createTempDirectory("private-auth");
		try (Git clone = cloneOver(httpUrl(owner, "private-clone"), authTarget,
			new UsernamePasswordCredentialsProvider(owner.username, token)))
		{
			assertTrue(Files.exists(authTarget.resolve("README.md")));
		}
	}

	@Test
	void revokedTokenIsRejected() throws Exception
	{
		User owner = persistUser();
		Repository repo = createRepo(owner, "revoked-token", Repository.Visibility.PRIVATE);
		seedCommit(service.repositoryPath(repo));
		AccessTokenService.CreatedToken created = createTokenEntity(owner);
		revokeToken(owner, created);

		Path target = Files.createTempDirectory("revoked");
		assertThrows(TransportException.class, () -> Git.cloneRepository()
			.setURI(httpUrl(owner, "revoked-token"))
			.setDirectory(target.toFile())
			.setCredentialsProvider(new UsernamePasswordCredentialsProvider(owner.username, created.plaintext()))
			.call());
	}

	private String httpUrl(User owner, String repoName)
	{
		return gitBase + "/" + owner.username + "/" + repoName + ".git";
	}

	@Transactional
	String createToken(User user)
	{
		return tokenService.create(user, "test").plaintext();
	}

	@Transactional
	AccessTokenService.CreatedToken createTokenEntity(User user)
	{
		return tokenService.create(user, "test");
	}

	@Transactional
	void revokeToken(User user, AccessTokenService.CreatedToken created)
	{
		tokenService.revoke(user, created.token().id);
	}

	private static Git cloneOver(String url, Path target, UsernamePasswordCredentialsProvider credentials)
		throws Exception
	{
		var cmd = Git.cloneRepository().setURI(url).setDirectory(target.toFile());
		if (credentials != null)
		{
			cmd.setCredentialsProvider(credentials);
		}
		return cmd.call();
	}

	private static void commitFile(Git git, Path work, String fileName) throws Exception
	{
		Files.writeString(work.resolve(fileName), "content\n");
		git.add().addFilepattern(".").call();
		git.commit().setMessage("add " + fileName).setSign(false)
			.setAuthor("t", "t@example.com").setCommitter("t", "t@example.com").call();
	}

	private static ObjectId mainRef(Path barePath) throws Exception
	{
		try (var repo = new FileRepositoryBuilder().setGitDir(barePath.toFile()).setMustExist(true).build())
		{
			return repo.resolve("refs/heads/main");
		}
	}

	@Transactional
	User persistUser()
	{
		String name = "http-" + UUID.randomUUID();
		User user = new User();
		user.oidcSub = "sub-" + name;
		user.username = name;
		user.persist();
		return user;
	}

	Repository createRepo(User owner, String name, Repository.Visibility visibility)
	{
		return service.create(owner, name, visibility, null);
	}

	public static void seedCommit(Path barePath) throws Exception
	{
		Path work = Files.createTempDirectory("seed");
		try (Git git = Git.cloneRepository().setURI(barePath.toUri().toString()).setDirectory(work.toFile()).call())
		{
			Files.writeString(work.resolve("README.md"), "hello git-shark\n");
			git.add().addFilepattern(".").call();
			git.commit().setMessage("init").setSign(false)
				.setAuthor("seed", "seed@example.com").setCommitter("seed", "seed@example.com").call();
			git.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
		}
	}

}
