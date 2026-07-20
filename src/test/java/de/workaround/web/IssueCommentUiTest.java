package de.workaround.web;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.CollaboratorService;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.IssueCommentService;
import de.workaround.git.IssueService;
import de.workaround.model.Issue;
import de.workaround.model.IssueComment;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.not;

@QuarkusTest
class IssueCommentUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	IssueService issueService;

	@Inject
	IssueCommentService comments;

	@Inject
	User.Repo userRepo;

	@Inject
	CollaboratorService collaboratorService;

	@Test
	@TestSecurity(user = "ic-collab")
	void aCollaboratorSeesTheDeleteControlOnAnotherUsersComment()
	{
		User collaborator = persistUser("ic-collab");
		User owner = persistUser("ic-owner-collab-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "board", Repository.Visibility.PUBLIC, null);
		addCollaborator(owner, repo, collaborator);
		Issue issue = issueService.create(owner, repo, "Team issue", null);
		IssueComment comment = comments.add(owner, issue, "owner wrote this");
		String detail = "/repos/" + owner.username + "/board/issues/" + issue.number;

		// a collaborator (write access, not the owner and not the author) may delete comments,
		// so the delete control must be rendered for them
		given().when().get(detail)
			.then().statusCode(200)
			.body(containsString(detail + "/comments/" + comment.id + "/delete"));
	}

	@Test
	@TestSecurity(user = "ic-owner")
	void ownerCanCommentOnAnIssueAndSeeItInline()
	{
		User owner = persistUser("ic-owner");
		Repository repo = service.create(owner, "board", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Discuss me", null);
		String detail = "/repos/" + owner.username + "/board/issues/" + issue.number;

		// the detail page carries a comment thread section and a compose form
		given().when().get(detail)
			.then().statusCode(200)
			.body(containsString("Comments"))
			.body(containsString(detail + "/comments"));

		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("body", "first thought")
			.when().post(detail + "/comments")
			.then().statusCode(303);

		given().when().get(detail)
			.then().statusCode(200)
			.body(containsString("first thought"))
			.body(containsString("ic-owner"));
	}

	@Test
	@TestSecurity(user = "ic-reader")
	void aReaderWhoIsNotTheOwnerCanCommentOnAPublicIssue()
	{
		persistUser("ic-reader");
		User owner = persistUser("ic-owner2-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "board", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Open to all", null);
		String detail = "/repos/" + owner.username + "/board/issues/" + issue.number;

		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("body", "outside remark")
			.when().post(detail + "/comments")
			.then().statusCode(303);

		given().when().get(detail).then().statusCode(200).body(containsString("outside remark"));
	}

	@Test
	void anonymousCannotComment()
	{
		User owner = persistUser("ic-anon-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = service.create(owner, "board", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "No anon", null);
		String detail = "/repos/" + owner.username + "/board/issues/" + issue.number;

		given().contentType("application/x-www-form-urlencoded").formParam("body", "sneaky")
			.when().post(detail + "/comments")
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = "ic-empty")
	void emptyCommentIsRejected()
	{
		User owner = persistUser("ic-empty");
		Repository repo = service.create(owner, "board", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Blank", null);
		String detail = "/repos/" + owner.username + "/board/issues/" + issue.number;

		given().contentType("application/x-www-form-urlencoded").formParam("body", "   ")
			.when().post(detail + "/comments")
			.then().statusCode(400);
	}

	@Test
	@TestSecurity(user = "ic-del")
	void authorCanDeleteTheirComment()
	{
		User owner = persistUser("ic-del");
		Repository repo = service.create(owner, "board", Repository.Visibility.PUBLIC, null);
		Issue issue = issueService.create(owner, repo, "Deletable", null);
		IssueComment comment = comments.add(owner, issue, "temporary note");
		String detail = "/repos/" + owner.username + "/board/issues/" + issue.number;

		given().when().get(detail).then().statusCode(200).body(containsString("temporary note"));

		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.when().post(detail + "/comments/" + comment.id + "/delete")
			.then().statusCode(303);

		given().when().get(detail).then().statusCode(200).body(not(containsString("temporary note")));
	}

	@Transactional
	void addCollaborator(User owner, Repository repo, User member)
	{
		collaboratorService.add(owner, repo, member.username);
	}

	@Transactional
	User persistUser(String name)
	{
		User existing = userRepo.findByOidcSubOptional(name).orElse(null);
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
