package de.workaround.model;

import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@QuarkusTest
class EntityPersistenceTest
{
	@Inject
	User.Repo users;

	@Inject
	Repository.Repo repositories;

	@Inject
	SshKey.Repo sshKeys;

	@Inject
	AccessToken.Repo accessTokens;

	@Test
	@TestTransaction
	void persistsUserWithGeneratedUuid()
	{
		User user = newUser("anna");

		user.persist();

		assertNotNull(user.id);
		User found = users.findByOidcSub("oidc-sub-anna");
		assertEquals("anna", found.username);
		assertEquals("Anna", found.displayName);
		assertEquals("anna@example.com", found.email);
	}

	@Test
	@TestTransaction
	void persistsRepositoryOwnedByUser()
	{
		User owner = newUser("bob");
		owner.persist();

		Repository repo = new Repository();
		repo.name = "project";
		repo.owner = owner;
		repo.visibility = Repository.Visibility.PUBLIC;
		repo.description = "demo";
		repo.persist();

		assertNotNull(repo.id);
		Repository found = repositories.findByOwnerAndName(owner, "project").orElseThrow();
		assertEquals("project", found.name);
		assertEquals(owner.id, found.owner.id);
		assertEquals(Repository.Visibility.PUBLIC, found.visibility);
	}

	@Test
	@TestTransaction
	void persistsSshKeyWithFingerprint()
	{
		User user = newUser("carol");
		user.persist();

		SshKey key = new SshKey();
		key.user = user;
		key.title = "laptop";
		key.publicKey = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIPlaceholder carol@laptop";
		key.fingerprint = "SHA256:fingerprint-carol";
		key.persist();

		assertNotNull(key.id);
		SshKey found = sshKeys.findByFingerprint("SHA256:fingerprint-carol").orElseThrow();
		assertEquals(user.id, found.user.id);
		assertEquals("laptop", found.title);
	}

	@Test
	@TestTransaction
	void persistsAccessTokenHash()
	{
		User user = newUser("dave");
		user.persist();

		AccessToken token = new AccessToken();
		token.user = user;
		token.tokenHash = "hash-dave";
		token.label = "ci";
		token.persist();

		assertNotNull(token.id);
		AccessToken found = accessTokens.findByTokenHash("hash-dave").orElseThrow();
		assertEquals(user.id, found.user.id);
		assertEquals("ci", found.label);
	}

	private static User newUser(String name)
	{
		User user = new User();
		user.oidcSub = "oidc-sub-" + name;
		user.username = name;
		user.displayName = name.substring(0, 1).toUpperCase() + name.substring(1);
		user.email = name + "@example.com";
		return user;
	}

}
