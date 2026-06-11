package de.workaround.ssh;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;

import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.arc.Arc;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

/**
 * Database access for SSH threads: activates the CDI request context (SSH worker threads have
 * none) and applies the shared access policy. Read requires canRead, receive-pack canWrite.
 */
@ApplicationScoped
public class SshGitBridge
{
	@Inject
	GitRepositoryService service;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	User.Repo users;

	public Optional<Path> resolveAuthorized(UUID userId, String rawPath, boolean write)
	{
		var requestContext = Arc.container().requestContext();
		boolean activated = !requestContext.isActive();
		if (activated)
		{
			requestContext.activate();
		}
		try
		{
			return lookup(userId, rawPath, write);
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
	Optional<Path> lookup(UUID userId, String rawPath, boolean write)
	{
		String[] parts = rawPath.split("/");
		if (parts.length != 2)
		{
			return Optional.empty();
		}
		User user = userId == null ? null : users.findById(userId);
		return service.find(parts[0], parts[1])
			.filter(repository -> write ? accessPolicy.canWrite(user, repository)
				: accessPolicy.canRead(user, repository))
			.map(service::repositoryPath);
	}

}
