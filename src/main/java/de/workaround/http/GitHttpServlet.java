package de.workaround.http;

import java.io.IOException;

import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;

import de.workaround.federation.FederationPushService;
import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.IssueCommitCloser;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.inject.Inject;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Serves the Git smart HTTP protocol under /git/<owner>/<repo>.git. Repositories are resolved
 * through {@link GitRepositoryService} (database is the single source of truth) and authorization
 * goes through the shared {@link AccessPolicy}. The authenticated user is taken from a request
 * attribute populated by the Basic-auth filter; absence means anonymous.
 */
@WebServlet(urlPatterns = "/git/*")
public class GitHttpServlet extends GitServlet
{
	public static final String ATTR_USER = "gitshark.user";

	public static final String ATTR_REPOSITORY = "gitshark.repository";

	@Inject
	GitRepositoryService service;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	FederationPushService pushService;

	@Inject
	IssueCommitCloser issueCloser;

	@Inject
	de.workaround.mirror.MirrorService mirrorService;

	@Override
	public void init(ServletConfig config) throws ServletException
	{
		setRepositoryResolver(this::resolve);
		setReceivePackFactory(this::createReceivePack);
		super.init(config);
	}

	private org.eclipse.jgit.lib.Repository resolve(HttpServletRequest request, String name)
		throws RepositoryNotFoundException, ServiceNotAuthorizedException
	{
		String[] parts = name.split("/");
		if (parts.length != 2)
		{
			throw new RepositoryNotFoundException(name);
		}
		Repository repository = service.find(parts[0], parts[1])
			.orElseThrow(() -> new RepositoryNotFoundException(name));

		User user = (User) request.getAttribute(ATTR_USER);
		if (!accessPolicy.canRead(user, repository))
		{
			if (user == null)
			{
				// trigger a Basic challenge so git clients retry with credentials
				throw new ServiceNotAuthorizedException();
			}
			// authenticated but not allowed: hide the repository
			throw new RepositoryNotFoundException(name);
		}
		request.setAttribute(ATTR_REPOSITORY, repository);
		try
		{
			return new FileRepositoryBuilder()
				.setGitDir(service.repositoryPath(repository).toFile())
				.setMustExist(true)
				.build();
		}
		catch (IOException e)
		{
			throw new RepositoryNotFoundException(name, e);
		}
	}

	private ReceivePack createReceivePack(HttpServletRequest request, org.eclipse.jgit.lib.Repository db)
		throws ServiceNotAuthorizedException, ServiceNotEnabledException
	{
		User user = (User) request.getAttribute(ATTR_USER);
		if (user == null)
		{
			throw new ServiceNotAuthorizedException();
		}
		Repository repository = (Repository) request.getAttribute(ATTR_REPOSITORY);
		if (repository == null || !accessPolicy.canWrite(user, repository))
		{
			throw new ServiceNotEnabledException();
		}
		ReceivePack receivePack = new ReceivePack(db);
		receivePack.setRefLogIdent(new org.eclipse.jgit.lib.PersonIdent(user.username, user.email != null ? user.email : user.username + "@git-shark"));
		// Capture identifiers while the request session is open; the hook fires after receive-pack.
		String ownerName = repository.owner.username;
		String repoName = repository.name;
		java.util.UUID pusherId = user.id;
		receivePack.setPostReceiveHook((rp, commands) -> {
			pushService.onPush(ownerName, repoName, pusherId, rp.getRepository(), commands);
			issueCloser.onPush(ownerName, repoName, pusherId, rp.getRepository(), commands);
			mirrorService.onPush(ownerName, repoName, commands);
		});
		return receivePack;
	}

}
