package de.workaround.account;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import io.quarkus.test.junit.QuarkusTest;

/**
 * PKCE must be enabled for the OIDC authorization code flow: kanidm (the production identity provider)
 * enforces PKCE for confidential clients. The state cookie that carries the PKCE code_verifier across
 * the redirect must be encrypted with an explicitly configured secret so multiple pods agree on the key.
 */
@QuarkusTest
class OidcPkceConfigTest {

	@Test
	void pkceIsRequiredForCodeFlow() {
		boolean pkceRequired = ConfigProvider.getConfig()
				.getValue("quarkus.oidc.authentication.pkce-required", Boolean.class);
		assertTrue(pkceRequired, "quarkus.oidc.authentication.pkce-required must be true");
	}

	@Test
	void stateCookieSecretIsConfigurable() {
		String secret = ConfigProvider.getConfig()
				.getOptionalValue("quarkus.oidc.authentication.state-secret", String.class)
				.orElse("");
		assertTrue(secret.length() >= 32, "state-secret must be >= 32 chars; was " + secret.length());
	}

	/**
	 * RP-Initiated Logout must NOT be configured: kanidm does not advertise an end_session_endpoint, and
	 * when quarkus.oidc.logout.path is set Quarkus validates that endpoint at startup and aborts boot if
	 * it is missing. Leaving logout.path unset keeps the app booting (local session logout only).
	 */
	@Test
	void rpInitiatedLogoutIsNotConfigured() {
		String logoutPath = ConfigProvider.getConfig()
				.getOptionalValue("quarkus.oidc.logout.path", String.class)
				.orElse("");
		assertTrue(logoutPath.isEmpty(),
				"quarkus.oidc.logout.path must be unset (kanidm has no end_session_endpoint); was " + logoutPath);
	}
}
