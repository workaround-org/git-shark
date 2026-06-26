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
}
