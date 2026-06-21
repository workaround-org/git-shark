package de.workaround.federation;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import de.workaround.model.FederationKey;
import de.workaround.model.RemoteActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Talks to remote ActivityPub servers: fetches and caches remote actors/keys (HTTPS-only, SSRF
 * guarded), and delivers signed activities to a remote inbox. Public actor documents are fetched
 * unsigned (they are public); only inbox POSTs are signed.
 */
@ApplicationScoped
public class ActivityPubClient
{
	private static final Duration CACHE_TTL = Duration.ofHours(6);

	private static final long MAX_RESPONSE_BYTES = 256 * 1024;

	@Inject
	RemoteUrlGuard guard;

	@Inject
	RemoteActor.Repo remoteActors;

	@Inject
	ObjectMapper mapper;

	private final HttpClient http = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		// HTTP/1.1: over HTTP/2 the signed `Host` header becomes the `:authority` pseudo-header and
		// peers can't reconstruct it for signature verification. HTTP/1.1 is the ActivityPub norm.
		.version(HttpClient.Version.HTTP_1_1)
		.followRedirects(HttpClient.Redirect.NORMAL)
		.build();

	/** Result of a delivery attempt. */
	public record DeliveryOutcome(boolean success, int status, String error)
	{
	}

	/** Fetches (or returns cached) the remote actor, refreshing past the TTL. */
	@Transactional
	public Optional<RemoteActor> fetchActor(String actorId)
	{
		Optional<RemoteActor> cached = remoteActors.findByActorId(actorId);
		if (cached.isPresent() && cached.get().fetchedAt.isAfter(Instant.now().minus(CACHE_TTL)))
		{
			return cached;
		}
		URI uri;
		try
		{
			uri = guard.requireSafe(actorId);
		}
		catch (RemoteUrlGuard.UnsafeUrlException e)
		{
			// Unsafe/unreachable actor → treat as not found rather than aborting the caller's transaction.
			return Optional.empty();
		}
		JsonNode doc;
		try
		{
			HttpResponse<String> response = http.send(
				HttpRequest.newBuilder(uri)
					.header("Accept", ActivityPubMedia.ACTIVITY_JSON)
					.timeout(Duration.ofSeconds(15))
					.GET().build(),
				HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() / 100 != 2 || response.body().length() > MAX_RESPONSE_BYTES)
			{
				return Optional.empty();
			}
			doc = mapper.readTree(response.body());
		}
		catch (IOException | InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return Optional.empty();
		}

		String inbox = doc.path("inbox").asText(null);
		String publicKeyPem = doc.path("publicKey").path("publicKeyPem").asText(null);
		if (inbox == null || publicKeyPem == null)
		{
			return Optional.empty();
		}
		RemoteActor actor = cached.orElseGet(RemoteActor::new);
		actor.actorId = actorId;
		actor.inbox = inbox;
		actor.publicKeyPem = publicKeyPem;
		actor.fetchedAt = Instant.now();
		if (actor.id == null)
		{
			actor.persist();
		}
		return Optional.of(actor);
	}

	/** Resolves the public key named by a {@code keyId} (actor id + {@code #main-key} fragment). */
	public Optional<PublicKey> fetchPublicKey(String keyId)
	{
		String actorId = keyId.contains("#") ? keyId.substring(0, keyId.indexOf('#')) : keyId;
		return fetchActor(actorId).map(actor -> ActorKeyService.parsePublic(actor.publicKeyPem));
	}

	/** Signs and POSTs the activity to the inbox, returning the outcome (never throws on network errors). */
	public DeliveryOutcome deliver(String inbox, byte[] payload, String keyId, PrivateKey signingKey)
	{
		URI uri;
		try
		{
			uri = guard.requireSafe(inbox);
		}
		catch (RemoteUrlGuard.UnsafeUrlException e)
		{
			return new DeliveryOutcome(false, 0, e.getMessage());
		}
		Map<String, String> signed = HttpSignatures.signPost(uri, payload, keyId, signingKey);
		HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
			.header("Content-Type", ActivityPubMedia.ACTIVITY_JSON)
			.timeout(Duration.ofSeconds(15))
			.POST(HttpRequest.BodyPublishers.ofByteArray(payload));
		signed.forEach((name, value) ->
		{
			// HttpClient manages Host itself; setting it is restricted, so skip it on the wire.
			if (!name.equalsIgnoreCase("Host"))
			{
				builder.header(name, value);
			}
		});
		try
		{
			HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			return new DeliveryOutcome(status / 100 == 2, status, status / 100 == 2 ? null : "HTTP " + status);
		}
		catch (IOException e)
		{
			return new DeliveryOutcome(false, 0, e.getMessage());
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return new DeliveryOutcome(false, 0, "interrupted");
		}
	}

	/** Convenience for signing as a local actor key. */
	public DeliveryOutcome deliver(String inbox, byte[] payload, FederationKey signer, String keyId)
	{
		PrivateKey key = ActorKeyService.parsePrivate(signer.privatePem);
		return deliver(inbox, payload, keyId, key);
	}

}
