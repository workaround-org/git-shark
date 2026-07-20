package de.workaround.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CloneUrlsTest
{
	@Test
	void sshUrlUsesScpShorthandForDefaultPort()
	{
		// scp-like shorthand (like GitHub) — shortest form, but git only accepts it on port 22.
		assertEquals("git@shark.example:workaround/gitshark.git",
			CloneUrls.ssh("shark.example", 22, "workaround", "gitshark"));
	}

	@Test
	void sshUrlKeepsNonDefaultPort()
	{
		// scp shorthand cannot express a port, so a non-default port forces the ssh:// form.
		assertEquals("ssh://git@shark.example:2222/workaround/gitshark.git",
			CloneUrls.ssh("shark.example", 2222, "workaround", "gitshark"));
	}
}
