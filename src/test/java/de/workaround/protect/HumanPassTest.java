package de.workaround.protect;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "you already solved a captcha" pass is a signed, self-expiring cookie value: no server-side
 * session state, and a forged or stale value must never grant the bypass.
 */
class HumanPassTest
{
	private static final Duration PASS = Duration.ofMinutes(30);

	@Test
	void anIssuedPassIsAccepted()
	{
		HumanPass pass = new HumanPass("captcha-secret", PASS, () -> 0L);

		assertTrue(pass.valid(pass.issue()));
	}

	@Test
	void aTamperedSignatureIsRejected()
	{
		HumanPass pass = new HumanPass("captcha-secret", PASS, () -> 0L);
		String issued = pass.issue();

		String tampered = issued.substring(0, issued.lastIndexOf('.') + 1) + "AAAAAAAAAAAAAAAAAAAAAA";

		assertFalse(pass.valid(tampered));
		assertFalse(pass.valid("garbage"));
		assertFalse(pass.valid(null));
	}

	@Test
	void aPassSignedWithAnotherSecretIsRejected()
	{
		String issued = new HumanPass("captcha-secret", PASS, () -> 0L).issue();

		assertFalse(new HumanPass("other-secret", PASS, () -> 0L).valid(issued));
	}

	@Test
	void anExpiredPassIsRejected()
	{
		AtomicLong now = new AtomicLong(0);
		HumanPass pass = new HumanPass("captcha-secret", PASS, now::get);
		String issued = pass.issue();

		now.set(PASS.toMillis() + 1_000);

		assertFalse(pass.valid(issued));
	}

	@Test
	void withoutASecretNoPassCanBeIssuedOrAccepted()
	{
		HumanPass pass = new HumanPass(null, PASS, () -> 0L);

		assertFalse(pass.available());
		assertFalse(pass.valid("0.whatever"));
		assertThrows(IllegalStateException.class, pass::issue);
	}
}
