package de.workaround.federation;

import java.net.URI;
import java.security.PublicKey;
import java.util.Optional;
import java.util.function.Function;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.workaround.model.InboxActivity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Inbound activity receipt: verifies the HTTP Signature (allowlisted peer, fetched key, valid
 * digest/date), deduplicates by activity id, then dispatches. Fails closed — any verification
 * problem yields 401 and no processing.
 */
@ApplicationScoped
public class InboxService
{
	public static final int ACCEPTED = 202;

	public static final int UNAUTHORIZED = 401;

	public static final int BAD_REQUEST = 400;

	@Inject
	FederationConfig config;

	@Inject
	ActivityPubClient client;

	@Inject
	InboxActivity.Repo inbox;

	@Inject
	ActivityDispatcher dispatcher;

	@Inject
	ObjectMapper mapper;

	/**
	 * Verifies and processes an inbound activity. {@code headerLookup} resolves a (lowercased)
	 * header name to its value; {@code path} is the raw request path that was signed.
	 */
	public int receive(byte[] body, Function<String, String> headerLookup, String method, String path)
	{
		HttpSignatures.SignatureHeader signature = HttpSignatures.parse(headerLookup.apply("signature"));
		if (signature == null)
		{
			return UNAUTHORIZED;
		}
		String signerHost = hostOf(signature.keyId());
		if (signerHost == null || !config.peerAllowed(signerHost))
		{
			return UNAUTHORIZED;
		}
		Optional<PublicKey> key = client.fetchPublicKey(signature.keyId());
		if (key.isEmpty() || !HttpSignatures.verify(signature, method, path, headerLookup, body, key.get()))
		{
			return UNAUTHORIZED;
		}

		JsonNode activity;
		try
		{
			activity = mapper.readTree(body);
		}
		catch (Exception e)
		{
			return BAD_REQUEST;
		}
		String activityId = activity.path("id").asText(null);
		if (activityId == null || activityId.isBlank())
		{
			return BAD_REQUEST;
		}
		return recordAndDispatch(activityId, activity);
	}

	@Transactional
	int recordAndDispatch(String activityId, JsonNode activity)
	{
		if (inbox.findByActivityId(activityId).isPresent())
		{
			return ACCEPTED; // already processed — idempotent no-op
		}
		InboxActivity record = new InboxActivity();
		record.activityId = activityId;
		record.persist();
		dispatcher.dispatch(activity);
		return ACCEPTED;
	}

	private static String hostOf(String keyId)
	{
		try
		{
			String actorId = keyId.contains("#") ? keyId.substring(0, keyId.indexOf('#')) : keyId;
			return URI.create(actorId).getHost();
		}
		catch (RuntimeException e)
		{
			return null;
		}
	}

}
