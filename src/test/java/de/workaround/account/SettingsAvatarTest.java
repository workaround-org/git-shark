package de.workaround.account;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class SettingsAvatarTest
{
	@Inject
	User.Repo users;

	@Inject
	EntityManager em;

	@ConfigProperty(name = "gitshark.storage.avatars")
	Path avatarRoot;

	@Test
	@TestSecurity(user = "avatar-up")
	void uploadsAndServesAvatar()
	{
		byte[] png = png();

		given().redirects().follow(false)
			.multiPart("avatar", "me.png", png, "image/png")
			.when().post("/settings/profile/avatar")
			.then().statusCode(anyOf(is(302), is(303)));

		User user = bySub("avatar-up");
		assertEquals("image/png", user.avatarContentType);
		assertNotNull(user.avatarUpdatedAt);
		assertTrue(Files.exists(avatarRoot.resolve(user.id.toString())), "avatar file written to disk");

		byte[] served = given()
			.when().get("/users/avatar-up/avatar")
			.then().statusCode(200)
			.contentType("image/png")
			.extract().asByteArray();
		assertArrayEquals(png, served);
	}

	@Test
	@TestSecurity(user = "avatar-big")
	void rejectsOversized()
	{
		byte[] big = new byte[2 * 1024 * 1024 + 1];
		byte[] magic = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
		System.arraycopy(magic, 0, big, 0, magic.length);

		given().redirects().follow(false)
			.multiPart("avatar", "big.png", big, "image/png")
			.when().post("/settings/profile/avatar")
			.then().statusCode(400);

		assertNull(bySub("avatar-big").avatarContentType, "oversized upload not persisted");
	}

	@Test
	@TestSecurity(user = "avatar-bad")
	void rejectsDisallowedType()
	{
		given().redirects().follow(false)
			.multiPart("avatar", "note.txt", "not an image".getBytes(), "text/plain")
			.when().post("/settings/profile/avatar")
			.then().statusCode(400);

		assertNull(bySub("avatar-bad").avatarContentType, "disallowed type not persisted");
	}

	@Test
	@TestSecurity(user = "avatar-spoof")
	void rejectsContentTypeMagicMismatch()
	{
		given().redirects().follow(false)
			.multiPart("avatar", "fake.png", "not really a png".getBytes(), "image/png")
			.when().post("/settings/profile/avatar")
			.then().statusCode(400);

		assertNull(bySub("avatar-spoof").avatarContentType, "spoofed magic bytes not persisted");
	}

	@Test
	@TestSecurity(user = "avatar-del")
	void removesAvatar()
	{
		given().redirects().follow(false)
			.multiPart("avatar", "me.png", png(), "image/png")
			.when().post("/settings/profile/avatar")
			.then().statusCode(anyOf(is(302), is(303)));

		User uploaded = bySub("avatar-del");
		assertNotNull(uploaded.avatarContentType);

		given().redirects().follow(false)
			.when().post("/settings/profile/avatar/delete")
			.then().statusCode(anyOf(is(302), is(303)));

		User cleared = bySub("avatar-del");
		assertNull(cleared.avatarContentType);
		assertNull(cleared.avatarUpdatedAt);
		assertFalse(Files.exists(avatarRoot.resolve(cleared.id.toString())), "avatar file deleted from disk");

		given().when().get("/users/avatar-del/avatar").then().statusCode(404);
	}

	@Test
	void missingAvatarIs404()
	{
		seedWithHandle("avatar-none-sub", "avatar-none");

		given().when().get("/users/avatar-none/avatar").then().statusCode(404);
	}

	// Clear the persistence context first: each HTTP call commits in its own transaction, so a
	// re-read of the same user within one test method must not return the stale L1-cached instance.
	private User bySub(String sub)
	{
		em.clear();
		return users.findByOidcSub(sub);
	}

	@Transactional
	void seedWithHandle(String sub, String handle)
	{
		if (users.findByOidcSubOptional(sub).isPresent())
		{
			return;
		}
		User user = new User();
		user.oidcSub = sub;
		user.username = handle;
		user.persist();
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
