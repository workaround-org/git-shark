package de.workaround.git;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;

import de.workaround.model.Issue;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class IssueCommitCloserTest
{
	@Inject
	IssueCommitCloser closer;

	@Inject
	IssueService issueService;

	@Inject
	GitRepositoryService service;

	@Inject
	Issue.Repo issues;

	@Inject
	User.Repo users;

	@Test
	void parsesGithubStyleClosingKeywordsCaseInsensitively()
	{
		assertEquals(Set.of(2), IssueCommitCloser.parseClosedIssues("fix #2"));
		assertEquals(Set.of(1, 3), IssueCommitCloser.parseClosedIssues("Closes #1 and resolves #3"));
		assertEquals(Set.of(9), IssueCommitCloser.parseClosedIssues("FIXED #9\n\nlong body"));
		assertTrue(IssueCommitCloser.parseClosedIssues("mentions #5 but no keyword").isEmpty());
		assertTrue(IssueCommitCloser.parseClosedIssues("nothing here").isEmpty());
	}

	@Test
	void pushingACommitWithAClosingKeywordMovesTheReferencedIssueToDone() throws Exception
	{
		User owner = persistUser("cc-alice-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "cc", Repository.Visibility.PUBLIC, null);
		issueService.create(owner, repo, "first", null); // #1
		issueService.create(owner, repo, "second", null); // #2

		Path bare = service.repositoryPath(repo);
		ObjectId head = GitTestSeeder.seedCommit(bare, "fix #2 the second thing\n");
		ReceiveCommand command = new ReceiveCommand(ObjectId.zeroId(), head, "refs/heads/main");
		command.setResult(ReceiveCommand.Result.OK);

		try (org.eclipse.jgit.lib.Repository db = new FileRepositoryBuilder().setGitDir(bare.toFile()).build())
		{
			closer.onPush(owner.username, "cc", owner.id, db, List.of(command));
		}

		assertEquals(Issue.Status.DONE, issues.findByRepositoryAndNumber(repo, 2).orElseThrow().status,
			"the commit referenced #2, so it must be closed");
		assertEquals(Issue.Status.PLANNED, issues.findByRepositoryAndNumber(repo, 1).orElseThrow().status,
			"#1 was not referenced and must stay open");
	}

	@Transactional
	User persistUser(String name)
	{
		User existing = users.findByOidcSubOptional(name).orElse(null);
		if (existing != null)
		{
			return existing;
		}
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.persist();
		return user;
	}

}
