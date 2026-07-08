package de.workaround.web;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.junit.jupiter.api.Test;

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
import static org.hamcrest.CoreMatchers.not;

@QuarkusTest
class MergeRequestCommentUiTest
{
	@Inject
	GitRepositoryService service;

	@Inject
	MergeRequestService mergeRequests;

	@Inject
	MergeRequestCommentService comments;

	@Inject
	User.Repo userRepo;

	@Test
	@TestSecurity(user = "cmu-owner")
	void ownerCanCommentOnADiffLineAndSeeItInline()
	{
		User owner = persistUser("cmu-owner");
		MergeRequest mr = seededMr(owner, "board");
		String detail = "/repos/" + owner.username + "/board/merge-requests/" + mr.id;

		// a commentable line exposes a hover comment icon (not a full-width placeholder bar)
		given().when().get(detail)
			.then().statusCode(200)
			.body(containsString("dl-comment-icon"))
			.body(not(containsString("Comment on this line</summary>")));

		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("filePath", "feature.txt").formParam("oldLine", "-1").formParam("newLine", "1")
			.formParam("body", "please rename this")
			.when().post(detail + "/comments")
			.then().statusCode(303);

		given().when().get(detail)
			.then().statusCode(200)
			.body(containsString("please rename this"))
			.body(containsString("feature.txt"));
	}

	@Test
	@TestSecurity(user = "cmu-reader")
	void aReaderWhoIsNotTheOwnerCanCommentOnAPublicMergeRequest()
	{
		persistUser("cmu-reader");
		User owner = persistUser("cmu-owner2-" + UUID.randomUUID().toString().substring(0, 8));
		MergeRequest mr = seededMr(owner, "board");
		String detail = "/repos/" + owner.username + "/board/merge-requests/" + mr.id;

		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.formParam("filePath", "feature.txt").formParam("oldLine", "-1").formParam("newLine", "1")
			.formParam("body", "outside review")
			.when().post(detail + "/comments")
			.then().statusCode(303);

		given().when().get(detail).then().statusCode(200).body(containsString("outside review"));
	}

	@Test
	void anonymousCannotComment()
	{
		User owner = persistUser("cmu-anon-" + UUID.randomUUID().toString().substring(0, 8));
		MergeRequest mr = seededMr(owner, "board");
		String detail = "/repos/" + owner.username + "/board/merge-requests/" + mr.id;

		given().contentType("application/x-www-form-urlencoded")
			.formParam("filePath", "feature.txt").formParam("oldLine", "-1").formParam("newLine", "1")
			.formParam("body", "sneaky")
			.when().post(detail + "/comments")
			.then().statusCode(403);
	}

	@Test
	@TestSecurity(user = "cmu-del")
	void authorCanDeleteTheirComment()
	{
		User owner = persistUser("cmu-del");
		MergeRequest mr = seededMr(owner, "board");
		MergeRequestComment comment = comments.add(owner, mr, "feature.txt", -1, 1, "temporary note");
		String detail = "/repos/" + owner.username + "/board/merge-requests/" + mr.id;

		given().when().get(detail).then().statusCode(200).body(containsString("temporary note"));

		given().redirects().follow(false).contentType("application/x-www-form-urlencoded")
			.when().post(detail + "/comments/" + comment.id + "/delete")
			.then().statusCode(303);

		given().when().get(detail).then().statusCode(200).body(not(containsString("temporary note")));
	}

	private MergeRequest seededMr(User owner, String name)
	{
		try
		{
			Repository repo = service.create(owner, name, Repository.Visibility.PUBLIC, null);
			Path bare = service.repositoryPath(repo);
			GitTestSeeder.seed(bare, Map.of("base.txt", "base\n".getBytes(StandardCharsets.UTF_8)));
			Path work = Files.createTempDirectory("cmuseed");
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
