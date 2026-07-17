package de.workaround.mirror;

import java.net.URI;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.eclipse.jgit.transport.ReceiveCommand;

import de.workaround.federation.FederationConfig;
import de.workaround.git.AccessPolicy;
import de.workaround.git.ForbiddenOperationException;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.MirrorSync;
import de.workaround.model.PushMirror;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.arc.Arc;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Owner-facing mirror management plus the async sync pipeline: a push enqueues a {@link MirrorSync}
 * (rapid pushes coalesce into one PENDING row — pushing the current state is always correct), a
 * scheduled worker drains due rows with exponential backoff, and exhausted rows are dead-lettered
 * without ever affecting the incoming push. Secrets are encrypted at rest via the entity's
 * {@link EncryptedStringConverter}; creation fails closed when no secret key is configured.
 */
@ApplicationScoped
public class MirrorService
{
	private static final Logger LOG = Logger.getLogger(MirrorService.class);

	@Inject
	PushMirror.Repo mirrors;

	@Inject
	MirrorSync.Repo syncs;

	@Inject
	GitRepositoryService repositories;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	MirrorPusher pusher;

	@Inject
	SecretCrypto crypto;

	@Inject
	FederationConfig federationConfig;

	@ConfigProperty(name = "gitshark.mirror.max-attempts", defaultValue = "8")
	int maxAttempts;

	@ConfigProperty(name = "gitshark.mirror.allow-insecure", defaultValue = "false")
	boolean allowInsecure;

	@Transactional
	public PushMirror create(User actor, Repository repository, String remoteUrl, PushMirror.AuthType authType,
		String username, String secret)
	{
		requireOwner(actor, repository);
		if (!crypto.available())
		{
			throw new IllegalStateException(
				"Push mirrors need GITSHARK_SECRET_KEY configured to store credentials encrypted");
		}
		MirrorUrlValidator.validate(remoteUrl, allowInsecure, ownHost());
		PushMirror mirror = new PushMirror();
		mirror.repository = repository;
		mirror.remoteUrl = remoteUrl.trim();
		mirror.authType = authType;
		if (authType == PushMirror.AuthType.HTTPS)
		{
			if (username == null || username.isBlank() || secret == null || secret.isBlank())
			{
				throw new IllegalArgumentException("HTTPS mirrors need a username and a password/token");
			}
			mirror.username = username.trim();
			mirror.secret = secret;
		}
		else
		{
			KeyPair key = MirrorKeys.generateEd25519();
			mirror.secret = MirrorKeys.privatePem(key.getPrivate());
			mirror.publicKey = MirrorKeys.sshPublicKey(key.getPublic());
		}
		mirror.persist();
		return mirror;
	}

	@Transactional
	public void delete(User actor, Repository repository, UUID mirrorId)
	{
		PushMirror mirror = requireMirror(actor, repository, mirrorId);
		// queue rows cascade at the DB level; deleting the row deletes the stored secret/keypair
		mirrors.deleteById(mirror.id);
	}

	/** Enqueues an immediate sync for a single mirror ("push now"). */
	@Transactional
	public void pushNow(User actor, Repository repository, UUID mirrorId)
	{
		PushMirror mirror = requireMirror(actor, repository, mirrorId);
		enqueue(mirror, Instant.now());
	}

	public List<PushMirror> list(Repository repository)
	{
		return mirrors.findByRepository(repository);
	}

	/**
	 * Entry point from the transports' post-receive hooks. Runs on a Git worker thread without a
	 * CDI request context (it activates one) and never throws into the Git path — mirror failures
	 * must not affect the incoming push.
	 */
	public void onPush(String ownerName, String repoName, Collection<ReceiveCommand> commands)
	{
		boolean anyRefUpdated = commands.stream().anyMatch(c -> c.getResult() == ReceiveCommand.Result.OK);
		if (!anyRefUpdated)
		{
			return;
		}
		var requestContext = Arc.container().requestContext();
		boolean activated = !requestContext.isActive();
		if (activated)
		{
			requestContext.activate();
		}
		try
		{
			enqueueForRepository(ownerName, repoName);
		}
		catch (RuntimeException e)
		{
			LOG.warnf(e, "Failed to enqueue mirror sync for %s/%s", ownerName, repoName);
		}
		finally
		{
			if (activated)
			{
				requestContext.terminate();
			}
		}
	}

	@Transactional
	void enqueueForRepository(String ownerName, String repoName)
	{
		Repository repository = repositories.find(ownerName, repoName).orElse(null);
		if (repository == null)
		{
			return;
		}
		for (PushMirror mirror : mirrors.findByRepository(repository))
		{
			if (mirror.enabled)
			{
				enqueue(mirror, Instant.now());
			}
		}
	}

	/** Coalescing enqueue: at most one PENDING sync per mirror; an existing one is pulled forward. */
	private void enqueue(PushMirror mirror, Instant when)
	{
		MirrorSync pending = syncs.findPending(mirror).orElse(null);
		if (pending != null)
		{
			if (pending.nextAttemptAt.isAfter(when))
			{
				pending.nextAttemptAt = when;
			}
			return;
		}
		MirrorSync sync = new MirrorSync();
		sync.mirror = mirror;
		sync.nextAttemptAt = when;
		sync.persist();
	}

	/** Scheduled drain pass — picks up due syncs and attempts each. */
	@Scheduled(every = "{gitshark.mirror.drain-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
	void drain()
	{
		drainOnce();
	}

	/** Attempts every currently-due sync once. Returns the number attempted. */
	public int drainOnce()
	{
		List<MirrorSync> due = findDue();
		for (MirrorSync sync : due)
		{
			attempt(sync.id);
		}
		return due.size();
	}

	@Transactional
	List<MirrorSync> findDue()
	{
		return syncs.findDue(Instant.now());
	}

	/** Attempts a single sync and records the outcome (state, attempts, backoff, mirror status). */
	@Transactional
	public void attempt(UUID syncId)
	{
		MirrorSync sync = syncs.findById(syncId);
		if (sync == null || sync.state != MirrorSync.State.PENDING)
		{
			return;
		}
		PushMirror mirror = sync.mirror;
		MirrorPusher.Result outcome = pusher.push(repositories.repositoryPath(mirror.repository), mirror);

		sync.attempts += 1;
		mirror.lastAttemptAt = Instant.now();
		if (outcome.success())
		{
			sync.state = MirrorSync.State.SYNCED;
			sync.lastError = null;
			mirror.lastSuccessAt = mirror.lastAttemptAt;
			mirror.lastError = null;
			if (mirror.hostKey == null && outcome.seenHostKey() != null)
			{
				// accept-new: pin the host key observed on the first successful contact
				mirror.hostKey = outcome.seenHostKey();
			}
		}
		else
		{
			sync.lastError = outcome.error();
			mirror.lastError = outcome.error();
			if (sync.attempts >= maxAttempts)
			{
				sync.state = MirrorSync.State.FAILED;
				LOG.warnf("Mirror sync to %s dead-lettered after %d attempts: %s", mirror.remoteUrl,
					sync.attempts, outcome.error());
			}
			else
			{
				sync.nextAttemptAt = Instant.now().plus(backoff(sync.attempts));
			}
		}
	}

	private PushMirror requireMirror(User actor, Repository repository, UUID mirrorId)
	{
		requireOwner(actor, repository);
		PushMirror mirror = mirrors.findById(mirrorId);
		if (mirror == null || !mirror.repository.id.equals(repository.id))
		{
			throw new ForbiddenOperationException("No such mirror on this repository");
		}
		return mirror;
	}

	private void requireOwner(User actor, Repository repository)
	{
		if (!accessPolicy.canAdmin(actor, repository))
		{
			throw new ForbiddenOperationException("Only the repository owner may manage mirrors");
		}
	}

	private String ownHost()
	{
		return federationConfig.validatedBaseUrl()
			.map(base -> URI.create(base).getHost())
			.orElse(null);
	}

	private static Duration backoff(int attempts)
	{
		// 1m, 2m, 4m, ... capped at 1h — same shape as the federation delivery queue
		long minutes = Math.min(60, 1L << Math.min(attempts - 1, 6));
		return Duration.ofMinutes(minutes);
	}

}
