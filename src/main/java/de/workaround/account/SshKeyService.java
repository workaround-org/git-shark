package de.workaround.account;

import java.security.PublicKey;
import java.util.List;
import java.util.UUID;

import org.apache.sshd.common.config.keys.AuthorizedKeyEntry;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.digest.BuiltinDigests;

import de.workaround.git.ForbiddenOperationException;
import de.workaround.model.SshKey;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Manages user SSH public keys: validates OpenSSH-format key material, computes the SHA-256
 * fingerprint used by the SSH authenticator, and enforces global key uniqueness.
 */
@ApplicationScoped
public class SshKeyService
{
	@Inject
	SshKey.Repo sshKeys;

	@Transactional
	public SshKey add(User user, String title, String openSshKeyText)
	{
		String fingerprint = fingerprintOf(openSshKeyText);
		if (sshKeys.findByFingerprint(fingerprint).isPresent())
		{
			throw new DuplicateSshKeyException(fingerprint);
		}
		SshKey key = new SshKey();
		key.user = user;
		key.title = title;
		key.publicKey = openSshKeyText.trim();
		key.fingerprint = fingerprint;
		key.persist();
		return key;
	}

	@Transactional
	public void remove(User actor, UUID keyId)
	{
		SshKey key = sshKeys.findById(keyId);
		if (key == null)
		{
			return;
		}
		if (!key.user.id.equals(actor.id))
		{
			throw new ForbiddenOperationException("Only the owner may remove an SSH key");
		}
		sshKeys.deleteById(keyId);
	}

	public List<SshKey> list(User user)
	{
		return sshKeys.findByUser(user);
	}

	private static String fingerprintOf(String openSshKeyText)
	{
		if (openSshKeyText == null || openSshKeyText.isBlank())
		{
			throw new InvalidSshKeyException("SSH key must not be empty");
		}
		try
		{
			AuthorizedKeyEntry entry = AuthorizedKeyEntry.parseAuthorizedKeyEntry(openSshKeyText.trim());
			PublicKey publicKey = entry.resolvePublicKey(null, null, null);
			return KeyUtils.getFingerPrint(BuiltinDigests.sha256, publicKey);
		}
		catch (Exception e)
		{
			throw new InvalidSshKeyException("Not a valid OpenSSH public key", e);
		}
	}

}
