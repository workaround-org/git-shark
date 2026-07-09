package de.workaround.web;

import de.workaround.model.Repository;

/**
 * Everything the repository sidebar needs on any repo sub-page: the repository itself, clone URLs, the current
 * request path (so the pin toggle returns the user to where they were), pin state, and the per-section counts shown
 * next to each nav item. Assembled once per request by {@link RepoNavService}.
 */
public record RepoNav(Repository repo, boolean loggedIn, boolean isOwner, boolean pinned, boolean empty,
	String defaultBranch, int commitCount, int branchCount, int tagCount, long openIssueCount, long openMrCount,
	String httpUrl, String sshUrl, String currentPath)
{
}
