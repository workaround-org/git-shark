package de.workaround.mirror;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Mirror targets are user-supplied URLs the server opens outbound connections to, so validation is
 * SSRF-relevant: only https and ssh (including scp-like syntax), never the instance itself, and no
 * private/loopback targets unless the dev-only insecure flag is set. IP-literal hosts are used
 * below so the checks do not depend on DNS.
 */
class MirrorUrlValidatorTest
{
	private static final String OWN_HOST = "shark.test";

	@Test
	void acceptsHttpsToPublicHost()
	{
		assertDoesNotThrow(() -> MirrorUrlValidator.validate("https://192.0.2.10/owner/repo.git", false, OWN_HOST));
	}

	@Test
	void acceptsSshUrlAndScpSyntax()
	{
		assertDoesNotThrow(() -> MirrorUrlValidator.validate("ssh://git@192.0.2.10/owner/repo.git", false, OWN_HOST));
		assertDoesNotThrow(() -> MirrorUrlValidator.validate("git@192.0.2.10:owner/repo.git", false, OWN_HOST));
	}

	@Test
	void rejectsHttpUnlessInsecureAllowed()
	{
		assertThrows(InvalidMirrorUrlException.class,
			() -> MirrorUrlValidator.validate("http://192.0.2.10/owner/repo.git", false, OWN_HOST));
		assertDoesNotThrow(() -> MirrorUrlValidator.validate("http://192.0.2.10/owner/repo.git", true, OWN_HOST));
	}

	@Test
	void rejectsOtherSchemes()
	{
		assertThrows(InvalidMirrorUrlException.class,
			() -> MirrorUrlValidator.validate("git://192.0.2.10/owner/repo.git", false, OWN_HOST));
		assertThrows(InvalidMirrorUrlException.class,
			() -> MirrorUrlValidator.validate("file:///tmp/elsewhere.git", false, OWN_HOST));
		assertThrows(InvalidMirrorUrlException.class,
			() -> MirrorUrlValidator.validate("not a url", false, OWN_HOST));
	}

	@Test
	void rejectsLoopbackAndPrivateTargetsUnlessInsecureAllowed()
	{
		assertThrows(InvalidMirrorUrlException.class,
			() -> MirrorUrlValidator.validate("https://127.0.0.1/owner/repo.git", false, OWN_HOST));
		assertThrows(InvalidMirrorUrlException.class,
			() -> MirrorUrlValidator.validate("ssh://git@10.0.0.5/owner/repo.git", false, OWN_HOST));
		assertDoesNotThrow(() -> MirrorUrlValidator.validate("https://127.0.0.1/owner/repo.git", true, OWN_HOST));
	}

	@Test
	void rejectsMirrorToOwnInstanceEvenInInsecureMode()
	{
		assertThrows(InvalidMirrorUrlException.class,
			() -> MirrorUrlValidator.validate("https://shark.test/git/owner/repo.git", false, OWN_HOST));
		assertThrows(InvalidMirrorUrlException.class,
			() -> MirrorUrlValidator.validate("https://SHARK.TEST/git/owner/repo.git", true, OWN_HOST));
	}

	@Test
	void rejectsMissingHost()
	{
		assertThrows(InvalidMirrorUrlException.class,
			() -> MirrorUrlValidator.validate("https:///owner/repo.git", false, OWN_HOST));
	}

}
