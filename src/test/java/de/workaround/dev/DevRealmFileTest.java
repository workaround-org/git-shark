package de.workaround.dev;

import java.io.InputStream;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the dev/test Keycloak realm shipped for {@code quarkus.keycloak.devservices.realm-path}. Without this
 * file the auto-provisioned realm's {@code quarkus-app} client lacks the {@code profile}/{@code email} client
 * scopes the app requests via {@code quarkus.oidc.authentication.scopes}, so every browser login fails with
 * {@code invalid_scope}. The file must therefore exist and grant both scopes to the client by default.
 */
class DevRealmFileTest
{
	@Test
	void quarkusAppClientGrantsProfileAndEmailScopesByDefault() throws Exception
	{
		try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("quarkus-realm.json"))
		{
			assertNotNull(in, "quarkus-realm.json must be on the classpath (referenced by devservices.realm-path)");
			JsonNode realm = new ObjectMapper().readTree(in);

			JsonNode client = null;
			for (JsonNode c : realm.path("clients"))
			{
				if ("quarkus-app".equals(c.path("clientId").asText()))
				{
					client = c;
					break;
				}
			}
			assertNotNull(client, "realm must define the quarkus-app client");

			List<String> defaultScopes = new ObjectMapper()
				.convertValue(client.path("defaultClientScopes"), List.class);
			assertTrue(defaultScopes.contains("profile"), "quarkus-app must grant the 'profile' scope by default");
			assertTrue(defaultScopes.contains("email"), "quarkus-app must grant the 'email' scope by default");
		}
	}
}
