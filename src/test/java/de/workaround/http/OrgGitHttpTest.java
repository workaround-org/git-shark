package de.workaround.http;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.InvalidRemoteException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.junit.jupiter.api.Test;

import de.workaround.account.OrganisationService;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Organisation;
import de.workaround.model.OrganisationMember;
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

/**
 * Org member levels over git smart HTTP: guests clone private org repositories but cannot push,
 * members push, non-members cannot even see private org repositories.
 */
@QuarkusTest
class OrgGitHttpTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	OrganisationService organisations;

	@Inject
	AccessTokenService tokenService;

	@TestHTTPResource("/git")
	URL gitBase;

	@Test
	void guestClonesPrivateOrgRepositoryButPushIsRejected() throws Exception
	{
		User creator = persistUser();
		User guest = persistUser();
		Organisation org = createOrg(creator);
		addMember(creator, org, guest, OrganisationMember.Role.GUEST);
		Repository repo = service.create(org, "org-guest", Repository.Visibility.PRIVATE, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));
		ObjectId before = mainRef(service.repositoryPath(repo));
		var credentials = new UsernamePasswordCredentialsProvider(guest.username, createToken(guest));

		Path work = Files.createTempDirectory("org-guest");
		try (Git git = Git.cloneRepository().setURI(httpUrl(org, "org-guest")).setDirectory(work.toFile())
			.setCredentialsProvider(credentials).call())
		{
			assertTrue(Files.exists(work.resolve("README.md")), "guest must clone the private org repository");
			commitFile(git, work, "guest.txt");
			assertThrows(TransportException.class, () -> git.push()
				.setCredentialsProvider(credentials)
				.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
				.call());
		}

		assertEquals(before, mainRef(service.repositoryPath(repo)), "refs must not change on rejected guest push");
	}

	@Test
	void memberPushesToPrivateOrgRepository() throws Exception
	{
		User creator = persistUser();
		User member = persistUser();
		Organisation org = createOrg(creator);
		addMember(creator, org, member, OrganisationMember.Role.MEMBER);
		Repository repo = service.create(org, "org-member", Repository.Visibility.PRIVATE, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));
		ObjectId before = mainRef(service.repositoryPath(repo));
		var credentials = new UsernamePasswordCredentialsProvider(member.username, createToken(member));

		Path work = Files.createTempDirectory("org-member");
		try (Git git = Git.cloneRepository().setURI(httpUrl(org, "org-member")).setDirectory(work.toFile())
			.setCredentialsProvider(credentials).call())
		{
			commitFile(git, work, "member.txt");
			git.push()
				.setCredentialsProvider(credentials)
				.setRefSpecs(new RefSpec("HEAD:refs/heads/main"))
				.call();
		}

		assertNotEquals(before, mainRef(service.repositoryPath(repo)), "member push must advance main");
	}

	@Test
	void nonMemberCannotClonePrivateOrgRepository() throws Exception
	{
		User creator = persistUser();
		User outsider = persistUser();
		Organisation org = createOrg(creator);
		Repository repo = service.create(org, "org-hidden", Repository.Visibility.PRIVATE, null);
		GitSmartHttpTest.seedCommit(service.repositoryPath(repo));
		var credentials = new UsernamePasswordCredentialsProvider(outsider.username, createToken(outsider));

		// authenticated but not allowed: the server hides the repository (404), which JGit
		// surfaces as InvalidRemoteException rather than an auth failure
		Path work = Files.createTempDirectory("org-outsider");
		assertThrows(InvalidRemoteException.class, () -> Git.cloneRepository()
			.setURI(httpUrl(org, "org-hidden"))
			.setDirectory(work.toFile())
			.setCredentialsProvider(credentials)
			.call());
	}

	private String httpUrl(Organisation org, String repoName)
	{
		return gitBase + "/" + org.name + "/" + repoName + ".git";
	}

	@Transactional
	Organisation createOrg(User creator)
	{
		return organisations.create(creator, "orghttp-" + UUID.randomUUID().toString().substring(0, 8), null);
	}

	@Transactional
	void addMember(User actor, Organisation org, User user, OrganisationMember.Role role)
	{
		organisations.addMember(actor, org, user.username, role);
	}

	@Transactional
	String createToken(User user)
	{
		return tokenService.create(user, "test").plaintext();
	}

	@Transactional
	User persistUser()
	{
		String name = "orghttp-" + UUID.randomUUID();
		User user = new User();
		user.oidcSub = "sub-" + name;
		user.username = name;
		user.persist();
		return user;
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

}
