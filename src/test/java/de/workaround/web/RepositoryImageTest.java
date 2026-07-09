package de.workaround.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import de.workaround.account.AvatarService;
import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.RepositoryImageService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class RepositoryImageTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	AvatarService avatars;

	@Inject
	RepositoryImageService imageService;

	@Inject
	Repository.Repo repositories;

	@Inject
	User.Repo users;

	@Inject
	EntityManager em;

	@ConfigProperty(name = "gitshark.storage.repo-images")
	Path imageRoot;

	@Test
	@TestSecurity(user = "img-owner")
	void ownerUploadsAndServesImage()
	{
		User owner = persistUser("img-owner");
		Repository repo = service.create(owner, "shark", Repository.Visibility.PUBLIC, null);
		byte[] png = png();

		given().redirects().follow(false)
			.multiPart("image", "logo.png", png, "image/png")
			.when().post("/repos/img-owner/shark/image")
			.then().statusCode(anyOf(is(302), is(303)));

		Repository stored = byId(repo.id);
		assertEquals("image/png", stored.imageContentType);
		assertNotNull(stored.imageUpdatedAt);
		assertTrue(Files.exists(imageRoot.resolve(repo.id.toString())), "image file written to disk");

		byte[] served = given()
			.when().get("/repos/img-owner/shark/image")
			.then().statusCode(200)
			.contentType("image/png")
			.extract().asByteArray();
		assertArrayEquals(png, served);
	}

	@Test
	@TestSecurity(user = "img-fallback")
	void withoutImageRepoFallsBackToOwnerAvatar()
	{
		User owner = persistUser("img-fallback");
		avatars.store(owner, png(), "image/png");
		service.create(owner, "plain", Repository.Visibility.PUBLIC, null);

		// no custom image => serving 404s and the repo renders the owner's avatar, not a repo image
		given().when().get("/repos/img-fallback/plain/image").then().statusCode(404);
		given().when().get("/repos/img-fallback/plain")
			.then().statusCode(200)
			.body(containsString("/users/img-fallback/avatar"))
			.body(not(containsString("/repos/img-fallback/plain/image")));
	}

	@Test
	@TestSecurity(user = "img-owner-set")
	void repoWithImageRendersItsOwnUrl()
	{
		User owner = persistUser("img-owner-set");
		service.create(owner, "branded", Repository.Visibility.PUBLIC, null);

		given().redirects().follow(false)
			.multiPart("image", "logo.png", png(), "image/png")
			.when().post("/repos/img-owner-set/branded/image")
			.then().statusCode(anyOf(is(302), is(303)));

		given().when().get("/repos/img-owner-set/branded")
			.then().statusCode(200)
			.body(containsString("/repos/img-owner-set/branded/image"));
	}

	@Test
	@TestSecurity(user = "img-stranger")
	void nonOwnerCannotUpload()
	{
		persistUser("img-stranger");
		User owner = persistUser("img-real-owner-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "guarded", Repository.Visibility.PUBLIC, null);

		given().redirects().follow(false)
			.multiPart("image", "logo.png", png(), "image/png")
			.when().post("/repos/" + owner.username + "/guarded/image")
			.then().statusCode(404);

		assertNull(byId(repo.id).imageContentType, "non-owner upload not persisted");
	}

	@Test
	@TestSecurity(user = "img-outsider")
	void privateRepoImageIsHiddenFromStrangers()
	{
		persistUser("img-outsider");
		User owner = persistUser("img-secret-owner-" + UUID.randomUUID().toString().substring(0, 8));
		service.create(owner, "secret", Repository.Visibility.PRIVATE, null);

		given().when().get("/repos/" + owner.username + "/secret/image").then().statusCode(404);
	}

	@Test
	@TestSecurity(user = "img-big")
	void rejectsOversized()
	{
		User owner = persistUser("img-big");
		Repository repo = service.create(owner, "big", Repository.Visibility.PUBLIC, null);
		byte[] big = new byte[2 * 1024 * 1024 + 1];
		byte[] magic = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
		System.arraycopy(magic, 0, big, 0, magic.length);

		given().redirects().follow(false)
			.multiPart("image", "big.png", big, "image/png")
			.when().post("/repos/img-big/big/image")
			.then().statusCode(400);

		assertNull(byId(repo.id).imageContentType, "oversized upload not persisted");
	}

	@Test
	@TestSecurity(user = "img-spoof")
	void rejectsContentTypeMagicMismatch()
	{
		User owner = persistUser("img-spoof");
		Repository repo = service.create(owner, "spoof", Repository.Visibility.PUBLIC, null);

		given().redirects().follow(false)
			.multiPart("image", "fake.png", "not really a png".getBytes(), "image/png")
			.when().post("/repos/img-spoof/spoof/image")
			.then().statusCode(400);

		assertNull(byId(repo.id).imageContentType, "spoofed magic bytes not persisted");
	}

	@Test
	@TestSecurity(user = "img-del")
	void removesImage()
	{
		User owner = persistUser("img-del");
		Repository repo = service.create(owner, "temp", Repository.Visibility.PUBLIC, null);

		given().redirects().follow(false)
			.multiPart("image", "logo.png", png(), "image/png")
			.when().post("/repos/img-del/temp/image")
			.then().statusCode(anyOf(is(302), is(303)));
		assertNotNull(byId(repo.id).imageContentType);

		given().redirects().follow(false)
			.when().post("/repos/img-del/temp/image/delete")
			.then().statusCode(anyOf(is(302), is(303)));

		Repository cleared = byId(repo.id);
		assertNull(cleared.imageContentType);
		assertNull(cleared.imageUpdatedAt);
		assertFalse(Files.exists(imageRoot.resolve(repo.id.toString())), "image file deleted from disk");

		given().when().get("/repos/img-del/temp/image").then().statusCode(404);
	}

	@Test
	@TestSecurity(user = "img-del-repo")
	void deletingRepositoryAlsoRemovesImageFile()
	{
		User owner = persistUser("img-del-repo");
		Repository repo = service.create(owner, "gone", Repository.Visibility.PUBLIC, null);

		given().redirects().follow(false)
			.multiPart("image", "logo.png", png(), "image/png")
			.when().post("/repos/img-del-repo/gone/image")
			.then().statusCode(anyOf(is(302), is(303)));
		assertTrue(Files.exists(imageRoot.resolve(repo.id.toString())));

		service.delete(owner, repo);

		assertFalse(Files.exists(imageRoot.resolve(repo.id.toString())), "image file removed with the repository");
	}

	@Test
	@TestSecurity(user = "img-webp")
	void rejectsRiffThatIsNotWebp()
	{
		User owner = persistUser("img-webp");
		Repository repo = service.create(owner, "webp", Repository.Visibility.PUBLIC, null);

		// "RIFF" container but the format tag at offset 8 is "AVI ", not "WEBP" — must be rejected
		byte[] riff = { 'R', 'I', 'F', 'F', 0, 0, 0, 0, 'A', 'V', 'I', ' ', 0, 0, 0, 0 };

		given().redirects().follow(false)
			.multiPart("image", "fake.webp", riff, "image/webp")
			.when().post("/repos/img-webp/webp/image")
			.then().statusCode(400);

		assertNull(byId(repo.id).imageContentType, "non-WebP RIFF file not persisted");
	}

	@Test
	@TestSecurity(user = "img-svc")
	void serviceRejectsNonOwnerActor()
	{
		User owner = persistUser("img-svc-owner-" + UUID.randomUUID().toString().substring(0, 8));
		User stranger = persistUser("img-svc-stranger-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "svc", Repository.Visibility.PUBLIC, null);

		assertThrows(ForbiddenOperationException.class,
			() -> imageService.store(stranger, repo, png(), "image/png"));
		assertFalse(Files.exists(imageRoot.resolve(repo.id.toString())), "rejected upload writes no file");
	}

	// Clear the persistence context first: each HTTP call commits in its own transaction, so a
	// re-read of the same repository within one test method must not return the stale L1-cached instance.
	private Repository byId(UUID id)
	{
		em.clear();
		return repositories.findById(id);
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

	private static byte[] png()
	{
		try
		{
			BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException e)
		{
			throw new UncheckedIOException(e);
		}
	}
}
