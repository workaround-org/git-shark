package de.workaround.ci;

import java.util.List;
import java.util.UUID;

import de.workaround.mirror.SecretCrypto;
import de.workaround.model.ActionSecret;
import de.workaround.model.ActionVariable;
import de.workaround.model.Repository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * CRUD for repository CI secrets and variables (issue #2, phase 2). Callers must have already gated on
 * repository-admin access; this layer validates input, enforces name uniqueness, encrypts secret
 * values at rest, and only ever deletes rows belonging to the given repository. Validation problems
 * are signalled with {@link IllegalArgumentException}; a missing encryption key with
 * {@link IllegalStateException} — both surfaced to the user as a 400 by the resource.
 */
@ApplicationScoped
public class ActionSecretService
{
	@Inject
	ActionSecret.Repo secrets;

	@Inject
	ActionVariable.Repo variables;

	@Inject
	SecretCrypto crypto;

	public List<ActionSecret> listSecrets(Repository repository)
	{
		return secrets.findByRepository(repository);
	}

	public List<ActionVariable> listVariables(Repository repository)
	{
		return variables.findByRepository(repository);
	}

	@Transactional
	public void addSecret(Repository repository, String name, String value)
	{
		String cleanName = requireName(name);
		requireValue(value);
		if (!crypto.available())
		{
			throw new IllegalStateException("CI secrets need GITSHARK_SECRET_KEY configured to store them encrypted");
		}
		if (secrets.findByRepositoryAndName(repository, cleanName).isPresent())
		{
			throw new IllegalArgumentException("A secret named \"" + cleanName + "\" already exists");
		}
		ActionSecret secret = new ActionSecret();
		secret.repository = repository;
		secret.name = cleanName;
		secret.valueEncrypted = crypto.encrypt(value);
		secret.persist();
	}

	@Transactional
	public void deleteSecret(Repository repository, UUID id)
	{
		ActionSecret secret = secrets.findById(id);
		if (secret != null && secret.repository.id.equals(repository.id))
		{
			secret.delete();
		}
	}

	@Transactional
	public void addVariable(Repository repository, String name, String value)
	{
		String cleanName = requireName(name);
		requireValue(value);
		if (variables.findByRepositoryAndName(repository, cleanName).isPresent())
		{
			throw new IllegalArgumentException("A variable named \"" + cleanName + "\" already exists");
		}
		ActionVariable variable = new ActionVariable();
		variable.repository = repository;
		variable.name = cleanName;
		variable.value = value;
		variable.persist();
	}

	@Transactional
	public void deleteVariable(Repository repository, UUID id)
	{
		ActionVariable variable = variables.findById(id);
		if (variable != null && variable.repository.id.equals(repository.id))
		{
			variable.delete();
		}
	}

	private static String requireName(String name)
	{
		String trimmed = name == null ? "" : name.trim();
		if (trimmed.isEmpty())
		{
			throw new IllegalArgumentException("A name is required");
		}
		if (!trimmed.matches("[A-Za-z_][A-Za-z0-9_]*"))
		{
			throw new IllegalArgumentException(
				"Name must start with a letter or underscore and contain only letters, digits and underscores");
		}
		return trimmed;
	}

	private static void requireValue(String value)
	{
		if (value == null || value.isEmpty())
		{
			throw new IllegalArgumentException("A value is required");
		}
	}
}
