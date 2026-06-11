package de.workaround.ssh;

import java.security.PublicKey;
import java.util.UUID;

import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.session.ServerSession;

import de.workaround.model.SshKey;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import io.quarkus.arc.Arc;

/**
 * Authenticates SSH sessions exclusively by public key: the offered key's SHA-256 fingerprint
 * is looked up in the ssh_keys table. On success the owning user's id is attached to the
 * session for later authorization in the Git command.
 */
@ApplicationScoped
public class GitSshAuthenticator implements PublickeyAuthenticator
{
	public static final AttributeRepository.AttributeKey<UUID> USER_ID = new AttributeRepository.AttributeKey<>();

	@Inject
	SshKey.Repo sshKeys;

	@Override
	public boolean authenticate(String username, PublicKey key, ServerSession session)
	{
		var requestContext = Arc.container().requestContext();
		boolean activated = !requestContext.isActive();
		if (activated)
		{
			requestContext.activate();
		}
		try
		{
			return lookup(key, session);
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
	boolean lookup(PublicKey key, ServerSession session)
	{
		String fingerprint = KeyUtils.getFingerPrint(BuiltinDigests.sha256, key);
		return sshKeys.findByFingerprint(fingerprint)
			.map(sshKey -> {
				session.setAttribute(USER_ID, sshKey.user.id);
				return true;
			})
			.orElse(false);
	}

}
