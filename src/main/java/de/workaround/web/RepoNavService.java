package de.workaround.web;

import java.nio.file.Path;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.GitBrowseService;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.IssueService;
import de.workaround.git.MergeRequestService;
import de.workaround.git.RepositoryPinService;
import de.workaround.model.Repository;
import de.workaround.model.User;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Builds the {@link RepoNav} rendered by the repository sidebar, which is shown on every repo-scoped page (code,
 * commits, branches, issues, merge requests). Centralised here so all three web resources compute the counts, clone
 * URLs and pin state the same way.
 */
@ApplicationScoped
public class RepoNavService
{
	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService service;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	GitBrowseService browse;

	@Inject
	RepositoryPinService pinService;

	@Inject
	IssueService issueService;

	@Inject
	MergeRequestService mergeRequestService;

	@ConfigProperty(name = "gitshark.ssh.port")
	int sshPort;

	public RepoNav build(Repository repo, UriInfo uriInfo)
	{
		Path path = service.repositoryPath(repo);
		boolean empty = browse.isEmpty(path);
		String defaultBranch = empty ? null : browse.defaultBranch(path);
		User user = currentUser.get();
		boolean loggedIn = user != null;
		boolean isOwner = loggedIn && accessPolicy.canAdmin(user, repo);
		boolean pinned = loggedIn && pinService.isPinned(user, repo);
		int commitCount = empty ? 0 : browse.commitCount(path, defaultBranch);
		int branchCount = browse.branches(path).size();
		int tagCount = browse.tags(path).size();
		long openIssueCount = issueService.countOpen(repo);
		long openMrCount = mergeRequestService.countOpen(repo);
		String httpUrl = uriInfo.getBaseUri().resolve("/git/" + repo.ownerHandle() + "/" + repo.name + ".git")
			.toString();
		String sshUrl = "ssh://git@" + uriInfo.getBaseUri().getHost() + ":" + sshPort + "/" + repo.ownerHandle()
			+ "/" + repo.name + ".git";
		return new RepoNav(repo, loggedIn, isOwner, pinned, empty, defaultBranch, commitCount, branchCount, tagCount,
			openIssueCount, openMrCount, httpUrl, sshUrl, uriInfo.getRequestUri().getRawPath());
	}
}
