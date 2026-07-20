package de.workaround.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

import de.workaround.git.CollaboratorService;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
import de.workaround.git.MergeRequestCommentService;
import de.workaround.git.MergeRequestService;
import de.workaround.model.MergeRequest;
import de.workaround.model.MergeRequestComment;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.containsString;

@QuarkusTest
class MergeRequestDiscussionUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	MergeRequestService mergeRequests;

	@Inject
	User.Repo userRepo;

	@Inject
	CollaboratorService collaboratorService;

	@Inject
	MergeRequestCommentService commentService;

	@Test
	@TestSecurity(user = "mrd-collab")
	void aCollaboratorSeesTheDeleteControlOnAnotherUsersDiscussionComment()
	{
		User collaborator = persistUser("mrd-collab");
		User owner = persistUser("mrd-owner-collab-" + UUID.randomUUID().toString().substring(0, 8));
		MergeRequest mr = seededMr(owner, "board");
		addCollaborator(owner, mr.repository, collaborator);
		MergeRequestComment comment = addDiscussion(owner, mr, "owner note on the MR");
		String detail = "/repos/" + owner.username + "/board/merge-requests/" + mr.number;

		// a collaborator (write access, not the owner and not the author) may delete comments,
		// so the delete control must be rendered for them
		given().when().get(detail)
			.then().statusCode(200)
			.body(containsString(detail + "/comments/" + comment.id + "/delete"));
	}

	@Test
	@TestSecurity(user = "mrd-owner")
	void ownerCanPostAGeneralDiscussionCommentAndSeeIt()
	{
		User owner = persistUser("mrd-owner");
		MergeRequest mr = seededMr(owner, "board");
		String detail = "/repos/" + owner.username + "/board/merge-requests/" + mr.number;

		// a general discussion thread (distinct from per-line review comments) with a compose form
		given().when().get(detail)
			.then().statusCode(200)
			.body(containsString("Discussion"))
			.body(containsString(detail + "/discussion"));

		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("body", "general remark on this MR")
			.when().post(detail + "/discussion")
			.then().statusCode(303);

		given().when().get(detail)
			.then().statusCode(200)
			.body(containsString("general remark on this MR"))
			.body(containsString("mrd-owner"));
	}

	@Test
	void anonymousCannotPostDiscussion()
	{
		User owner = persistUser("mrd-anon-" + UUID.randomUUID().toString().substring(0, 8));
		MergeRequest mr = seededMr(owner, "board");
		String detail = "/repos/" + owner.username + "/board/merge-requests/" + mr.number;

		given().contentType("application/x-www-form-urlencoded").formParam("body", "sneaky")
			.when().post(detail + "/discussion")
			.then().statusCode(403);
	}

	private MergeRequest seededMr(User owner, String name)
	{
		try
		{
			Repository repo = service.create(owner, name, Repository.Visibility.PUBLIC, null);
			Path bare = service.repositoryPath(repo);
			GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)));
			Path work = Files.createTempDirectory("mrdseed");
			try (Git git = Git.cloneRepository().setURI(bare.toUri().toString()).setDirectory(work.toFile()).call())
			{
				git.checkout().setCreateBranch(true).setName("feature").call();
				Files.writeString(work.resolve("feature.txt"), "feature\n");
				git.add().addFilepattern(".").call();
				git.commit().setMessage("feature work").setSign(false)
					.setAuthor("dev", "dev@example.com").setCommitter("dev", "dev@example.com").call();
				git.push().setRefSpecs(new RefSpec("feature:refs/heads/feature")).call();
			}
			return mergeRequests.create(owner, repo, "Review me", null, "feature", "main");
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
	}

	@Transactional
	void addCollaborator(User owner, Repository repo, User member)
	{
		collaboratorService.add(owner, repo, member.username);
	}

	@Transactional
	MergeRequestComment addDiscussion(User author, MergeRequest mr, String body)
	{
		return commentService.addGeneral(author, mr, body);
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
