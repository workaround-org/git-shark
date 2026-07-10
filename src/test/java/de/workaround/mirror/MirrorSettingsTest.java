package de.workaround.mirror;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.model.PushMirror;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner-only mirror management on the repository page: create (HTTPS and SSH), delete, and the
 * secrecy guarantees — secrets are encrypted at rest and never rendered back into the UI.
 */
@QuarkusTest
class MirrorSettingsTest
{
	private static final String OWNER = "mirror-owner";

	private static final String STRANGER = "mirror-stranger";

	@Inject
	GitRepositoryService service;

	@Inject
	User.Repo users;

	@Inject
	PushMirror.Repo mirrors;

	@Inject
	MirrorService mirrorService;

	@Inject
	EntityManager em;

	@Test
	@TestSecurity(user = OWNER)
	void ownerCreatesHttpsMirrorAndSecretStaysHidden()
	{
		Repository repo = ownedRepo("settings-https");

		given()
			.redirects().follow(false)
			.formParam("url", "https://mirror-target.example/owner/repo.git")
			.formParam("authType", "HTTPS")
			.formParam("username", "deploy")
			.formParam("secret", "plaintext-token-42")
			.when().post("/repos/" + OWNER + "/" + repo.name + "/mirrors")
			.then().statusCode(303);

		List<PushMirror> created = listMirrors(repo);
		assertEquals(1, created.size());
		assertEquals(PushMirror.AuthType.HTTPS, created.get(0).authType);

		String page = given()
			.when().get("/repos/" + OWNER + "/" + repo.name)
			.then().statusCode(200)
			.extract().asString();
		assertTrue(page.contains("https://mirror-target.example/owner/repo.git"), "mirror must be listed");
		assertFalse(page.contains("plaintext-token-42"), "secret must never be rendered");
	}

	@Test
	@TestSecurity(user = OWNER)
	void secretIsEncryptedAtRest()
	{
		Repository repo = ownedRepo("settings-at-rest");

		given()
			.redirects().follow(false)
			.formParam("url", "https://mirror-target.example/owner/repo.git")
			.formParam("authType", "HTTPS")
			.formParam("username", "deploy")
			.formParam("secret", "plaintext-token-at-rest")
			.when().post("/repos/" + OWNER + "/" + repo.name + "/mirrors")
			.then().statusCode(303);

		String stored = storedSecretColumn(listMirrors(repo).get(0).id);
		assertFalse(stored.contains("plaintext-token-at-rest"), "DB column must not contain the plaintext");
	}

	@Test
	@TestSecurity(user = OWNER)
	void sshMirrorGeneratesKeypairAndShowsPublicKey()
	{
		Repository repo = ownedRepo("settings-ssh");

		given()
			.redirects().follow(false)
			.formParam("url", "ssh://git@mirror-target.example/owner/repo.git")
			.formParam("authType", "SSH")
			.when().post("/repos/" + OWNER + "/" + repo.name + "/mirrors")
			.then().statusCode(303);

		PushMirror mirror = listMirrors(repo).get(0);
		assertTrue(mirror.publicKey.startsWith("ssh-ed25519 "), "public key must be OpenSSH Ed25519");

		given()
			.when().get("/repos/" + OWNER + "/" + repo.name)
			.then().statusCode(200)
			.body(containsString("ssh-ed25519 "))
			.body(not(containsString("BEGIN PRIVATE KEY")));
	}

	@Test
	@TestSecurity(user = OWNER)
	void invalidMirrorUrlIsRejected()
	{
		Repository repo = ownedRepo("settings-bad-url");

		given()
			.redirects().follow(false)
			.formParam("url", "git://mirror-target.example/owner/repo.git")
			.formParam("authType", "HTTPS")
			.formParam("username", "deploy")
			.formParam("secret", "token")
			.when().post("/repos/" + OWNER + "/" + repo.name + "/mirrors")
			.then().statusCode(400);

		assertEquals(0, listMirrors(repo).size());
	}

	@Test
	@TestSecurity(user = OWNER)
	void httpsMirrorRequiresCredentials()
	{
		Repository repo = ownedRepo("settings-no-secret");

		given()
			.redirects().follow(false)
			.formParam("url", "https://mirror-target.example/owner/repo.git")
			.formParam("authType", "HTTPS")
			.when().post("/repos/" + OWNER + "/" + repo.name + "/mirrors")
			.then().statusCode(400);

		assertEquals(0, listMirrors(repo).size());
	}

	@Test
	@TestSecurity(user = OWNER)
	void ownerDeletesMirror()
	{
		Repository repo = ownedRepo("settings-delete");
		PushMirror mirror = createMirror(repo);

		given()
			.redirects().follow(false)
			.when().post("/repos/" + OWNER + "/" + repo.name + "/mirrors/" + mirror.id + "/delete")
			.then().statusCode(303);

		assertEquals(0, listMirrors(repo).size());
	}

	@Test
	@TestSecurity(user = STRANGER)
	void strangerCannotCreateOrDeleteMirrors()
	{
		persistUser(STRANGER);
		Repository repo = repoOwnedBy(OWNER, "settings-foreign");
		PushMirror mirror = createMirror(repo);

		given()
			.redirects().follow(false)
			.formParam("url", "https://mirror-target.example/owner/repo.git")
			.formParam("authType", "HTTPS")
			.formParam("username", "deploy")
			.formParam("secret", "token")
			.when().post("/repos/" + OWNER + "/" + repo.name + "/mirrors")
			.then().statusCode(404);

		given()
			.redirects().follow(false)
			.when().post("/repos/" + OWNER + "/" + repo.name + "/mirrors/" + mirror.id + "/delete")
			.then().statusCode(404);

		assertEquals(1, listMirrors(repo).size());
	}

	@Test
	void anonymousCannotManageMirrors()
	{
		Repository repo = repoOwnedBy(OWNER, "settings-anon");

		given()
			.redirects().follow(false)
			.formParam("url", "https://mirror-target.example/owner/repo.git")
			.formParam("authType", "HTTPS")
			.formParam("username", "deploy")
			.formParam("secret", "token")
			.when().post("/repos/" + OWNER + "/" + repo.name + "/mirrors")
			.then().statusCode(404);
	}

	@Test
	@TestSecurity(user = STRANGER)
	void mirrorsAreNotShownToNonOwners()
	{
		persistUser(STRANGER);
		Repository repo = repoOwnedBy(OWNER, "settings-hidden");
		createMirror(repo);

		given()
			.when().get("/repos/" + OWNER + "/" + repo.name)
			.then().statusCode(200)
			.body(not(containsString("Push mirrors")));
	}

	private Repository ownedRepo(String name)
	{
		return repoOwnedBy(OWNER, name);
	}

	private Repository repoOwnedBy(String username, String name)
	{
		User owner = persistUser(username);
		return service.create(owner, name + "-" + UUID.randomUUID().toString().substring(0, 8),
			Repository.Visibility.PUBLIC, null);
	}

	private PushMirror createMirror(Repository repo)
	{
		return mirrorService.create(repo.ownerUser, repo, "https://mirror-target.example/owner/repo.git",
			PushMirror.AuthType.HTTPS, "deploy", "token");
	}

	@Transactional
	List<PushMirror> listMirrors(Repository repo)
	{
		return mirrors.findByRepository(repo);
	}

	@Transactional
	String storedSecretColumn(UUID mirrorId)
	{
		return (String) em.createNativeQuery("select encrypted_secret from push_mirror where id = :id")
			.setParameter("id", mirrorId)
			.getSingleResult();
	}

	@Transactional
	User persistUser(String name)
	{
		User existing = users.findByOidcSubOptional(name).orElse(null);
		if (existing != null)
		{
			return existing;
		}
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.persist();
		return user;
	}

}
