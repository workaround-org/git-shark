package de.workaround.federation;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import de.workaround.model.FederationKey;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the restricted-header bug: a signed activity delivered through the real
 * {@link ActivityPubClient} (java.net.http) to this instance's own inbox must verify and be
 * accepted (202). Without permitting the restricted {@code Date}/{@code Host} headers, HttpClient
 * drops the signed {@code date} and the receiver returns 401. Self-delivery works because the
 * signer key is fetched from this same instance.
 */
@QuarkusTest
@TestProfile(FederationDeliveryRoundTripTest.SelfDeliveryProfile.class)
class FederationDeliveryRoundTripTest
{
	public static class SelfDeliveryProfile implements QuarkusTestProfile
	{
		@Override
		public Map<String, String> getConfigOverrides()
		{
			return Map.of(
				"quarkus.http.test-port", "8081",
				"gitshark.federation.enabled", "true",
				"gitshark.federation.base-url", "http://localhost:8081",
				"gitshark.federation.dev-allow-insecure", "true",
				"gitshark.federation.peer-allowlist", "localhost,127.0.0.1");
		}
	}

	@Inject
	ActivityPubClient client;

	@Inject
	ActorKeyService keyService;

	@Inject
	ActorUris uris;

	@Inject
	ObjectMapper mapper;

	@Test
	void signedDeliveryThroughHttpClientIsAccepted() throws Exception
	{
		FederationKey key = keyService.getOrCreate(FederationKey.ActorType.INSTANCE, "instance");

		ObjectNode activity = mapper.createObjectNode();
		activity.put("@context", "https://www.w3.org/ns/activitystreams");
		activity.put("id", "http://localhost:8081/test/" + UUID.randomUUID());
		activity.put("type", "Create");
		byte[] payload = mapper.writeValueAsBytes(activity);

		ActivityPubClient.DeliveryOutcome outcome =
			client.deliver(uris.inbox(uris.instance()), payload, key, uris.keyId(uris.instance()));

		assertTrue(outcome.success(),
			"signed delivery through HttpClient must be accepted by the inbox; got " + outcome);
	}

}
