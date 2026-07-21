package de.workaround.dev;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.jboss.logging.Logger;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.IssueService;
import de.workaround.git.MergeRequestCommentService;
import de.workaround.git.MergeRequestService;
import de.workaround.http.AccessTokenService;
import de.workaround.model.AccessToken;
import de.workaround.model.Issue;
import de.workaround.model.MergeRequest;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Seeds a demo user (alice) owning a public "demo" repository with one commit, so a freshly
 * started dev instance is not empty. Enabled only when {@code gitshark.dev.seed-data=true}
 * (set under the {@code %dev} profile); idempotent, so restarts never duplicate data.
 */
@ApplicationScoped
public class DevDataSeeder
{
	private static final Logger LOG = Logger.getLogger(DevDataSeeder.class);

	private static final String DEMO_USER = "alice";
	private static final String DEMO_REPO = "demo";
	private static final String DEMO_BRANCH = "feature";

	/**
	 * A fixed personal access token seeded for the demo user so local API/Renovate testing needs no UI login.
	 * DEV ONLY: seeded exclusively under the {@code %dev} profile (guarded by {@code gitshark.dev.seed-data},
	 * true only in {@code %dev}), so it never exists in a production database.
	 */
	public static final String DEV_ACCESS_TOKEN = "gs_dev-only-local-renovate-token-0123456789";

	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo users;

	@Inject
	MergeRequestService mergeRequests;

	@Inject
	MergeRequestCommentService mergeRequestCommentService;

	@Inject
	MergeRequest.Repo mergeRequestRepo;

	@Inject
	IssueService issueService;

	@Inject
	AccessTokenService accessTokens;

	@Inject
	AccessToken.Repo accessTokenRepo;

	@ConfigProperty(name = "gitshark.dev.seed-data", defaultValue = "false")
	boolean enabled;

	void onStart(@Observes StartupEvent event)
	{
		if (enabled)
		{
			seed();
		}
	}

	/**
	 * Ensures the demo data exists. Safe to call repeatedly: the user and repository are created
	 * only when absent, and the seed commit is written only when the repository has no commits.
	 */
	@Transactional
	public void seed()
	{
		User alice = users.findByUsername(DEMO_USER).orElseGet(() -> {
			User created = new User();
			created.oidcSub = "dev:" + DEMO_USER;
			created.username = DEMO_USER;
			created.displayName = "Alice (demo)";
			created.email = DEMO_USER + "@demo.local";
			created.persist();
			return created;
		});

		Repository demo = repositories.find(alice.username, DEMO_REPO).orElse(null);
		if (demo == null)
		{
			demo = repositories.create(alice, DEMO_REPO, Repository.Visibility.PUBLIC,
				"Demo repository seeded for local development");
		}

		Path bare = repositories.repositoryPath(demo);
		if (hasNoCommits(bare))
		{
			seedInitialCommit(bare);
			LOG.infof("Seeded demo data: %s/%s with an initial commit", alice.username, demo.name);
		}

		seedMergeRequest(alice, demo, bare);
		seedIssues(alice, demo);
		seedDevAccessToken(alice);
	}

	/**
	 * Seeds the fixed {@link #DEV_ACCESS_TOKEN} for the given user so local API and Renovate testing needs no
	 * UI login. Idempotent: skipped when the user already has any token. DEV ONLY — see {@link #DEV_ACCESS_TOKEN}.
	 */
	@Transactional
	public void seedDevAccessToken(User user)
	{
		if (!accessTokenRepo.findByUser(user).isEmpty())
		{
			return;
		}
		AccessToken token = new AccessToken();
		token.user = user;
		token.label = "dev (local API/Renovate testing)";
		token.tokenHash = accessTokens.hash(DEV_ACCESS_TOKEN);
		token.persist();
		LOG.warnf("Seeded DEV access token for %s: %s  (DEV ONLY — never enable gitshark.dev.seed-data in production)",
			user.username, DEV_ACCESS_TOKEN);
	}

	/**
	 * Ensures a handful of demo issues exist so the issue list, status filters and Markdown rendering
	 * have something to show locally. Idempotent: seeded only while the repository has no issues at all.
	 */
	private void seedIssues(User alice, Repository demo)
	{
		if (!issueService.list(demo).isEmpty())
		{
			return;
		}
		issueService.create(alice, demo, "Add a CONTRIBUTING guide",
			"A short note on how to propose changes would help newcomers.");
		Issue inDevelopment = issueService.create(alice, demo, "Render Markdown in issue descriptions",
			"""
				Issue descriptions support **Markdown**, so this issue shows the common elements in one place.

				## Motivation

				A demo instance should let you see at a glance how formatted text renders:

				- **bold** and *italic* inline styles
				- [links](https://example.com) and `inline code`
				- nested lists
				  - like this one

				## Proposed approach

				1. Parse the description with the same renderer the README uses.
				2. Sanitize the resulting HTML.
				3. Cache the rendered fragment alongside the issue.

				> Rendering and sanitizing must stay in sync — an unsanitized renderer would be an XSS hole.

				```java
				String html = markdown.render(issue.description);
				```

				This description is intentionally long so list truncation and detail pages are easy to check.""");
		Issue done = issueService.create(alice, demo, "Seed a demo repository on dev startup",
			"""
				Done — a freshly started dev instance seeds `alice/demo` automatically.

				```bash
				./mvnw quarkus:dev
				```""");
		issueService.updateStatus(alice, inDevelopment, Issue.Status.IN_DEVELOPMENT);
		issueService.updateStatus(alice, done, Issue.Status.DONE);
		issueService.create(alice, demo, "Show an empty state on the issues page",
			"When a repository has no issues yet, the list should explain how to create the first one.");
		LOG.infof("Seeded demo issues on %s/%s", alice.username, demo.name);
	}

	/**
	 * Ensures a demo merge request exists so the merge-request UI has something to show locally. Idempotent: the
	 * {@value #DEMO_BRANCH} branch is pushed only when absent, and the merge request (plus a sample line comment) is
	 * created only when the repository has none yet.
	 */
	private void seedMergeRequest(User alice, Repository demo, Path bare)
	{
		if (!mergeRequestRepo.findByRepository(demo).isEmpty())
		{
			return;
		}
		if (!hasBranch(bare, DEMO_BRANCH))
		{
			seedFeatureBranch(bare);
		}
		MergeRequest mr = mergeRequests.create(alice, demo, "Add an overview section",
			"Adds a short overview file on the " + DEMO_BRANCH + " branch. Try reviewing the diff and merging it.",
			DEMO_BRANCH, "main");
		// anchor a sample comment to the first added line of the new file, so the comment UI is demoable too
		mergeRequestCommentService.add(alice, mr, "OVERVIEW.md", -1, 1, "Could we expand this a little?");
		LOG.infof("Seeded demo merge request !%d on %s/%s", mr.number, alice.username, demo.name);
	}

	private static boolean hasNoCommits(Path barePath)
	{
		try (Git git = Git.open(barePath.toFile()))
		{
			return git.getRepository().resolve("refs/heads/main") == null;
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Failed to inspect demo repository at " + barePath, e);
		}
	}

	private static boolean hasBranch(Path barePath, String branch)
	{
		try (Git git = Git.open(barePath.toFile()))
		{
			return git.getRepository().resolve("refs/heads/" + branch) != null;
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Failed to inspect demo repository at " + barePath, e);
		}
	}

	/** Branches off main, adds OVERVIEW.md and pushes the {@value #DEMO_BRANCH} branch, leaving main untouched. */
	private static void seedFeatureBranch(Path barePath)
	{
		Path work = null;
		try
		{
			work = Files.createTempDirectory("git-shark-seed-branch");
			try (Git git = Git.cloneRepository().setURI(barePath.toUri().toString()).setDirectory(work.toFile()).call())
			{
				git.checkout().setCreateBranch(true).setName(DEMO_BRANCH).call();
				Files.writeString(work.resolve("OVERVIEW.md"),
					"# Overview\n\nThis file was added on the " + DEMO_BRANCH + " branch to demo merge requests.\n");
				git.add().addFilepattern(".").call();
				git.commit().setMessage("Add overview section").setSign(false)
					.setAuthor(DEMO_USER, DEMO_USER + "@demo.local")
					.setCommitter(DEMO_USER, DEMO_USER + "@demo.local").call();
				git.push().setRefSpecs(new RefSpec(DEMO_BRANCH + ":refs/heads/" + DEMO_BRANCH)).call();
			}
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Failed to seed feature branch into " + barePath, e);
		}
		finally
		{
			deleteRecursively(work);
		}
	}

	private static void seedInitialCommit(Path barePath)
	{
		Path work = null;
		try
		{
			work = Files.createTempDirectory("git-shark-seed");
			try (Git git = Git.cloneRepository().setURI(barePath.toUri().toString()).setDirectory(work.toFile()).call())
			{
				Files.writeString(work.resolve("README.md"),
					"""
						# demo

						Demo repository seeded for local development. It exists so a freshly started
						dev instance has something to browse: a README, issues, and an open merge request.

						## What's in here

						- This `README.md` on `main`
						- An `OVERVIEW.md` waiting on the `feature` branch (see the open merge request)
						- A few issues in different states with Markdown descriptions

						## Try it

						Clone the repository and push a change:

						```bash
						git clone http://localhost:8080/alice/demo.git
						cd demo
						git commit --allow-empty -m "Test commit"
						git push
						```

						## Notes

						Everything in this repository is seeded by `DevDataSeeder` on startup and only
						when missing, so restarts never duplicate data.
						""");
				git.add().addFilepattern(".").call();
				git.commit().setMessage("Initial commit").setSign(false)
					.setAuthor(DEMO_USER, DEMO_USER + "@demo.local")
					.setCommitter(DEMO_USER, DEMO_USER + "@demo.local").call();
				git.push().setRefSpecs(new RefSpec("HEAD:refs/heads/main")).call();
			}
		}
		catch (Exception e)
		{
			throw new IllegalStateException("Failed to seed initial commit into " + barePath, e);
		}
		finally
		{
			deleteRecursively(work);
		}
	}

	private static void deleteRecursively(Path root)
	{
		if (root == null)
		{
			return;
		}
		try (var paths = Files.walk(root))
		{
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
				try
				{
					Files.deleteIfExists(p);
				}
				catch (Exception ignored)
				{
					// best-effort cleanup of a temp working clone
				}
			});
		}
		catch (Exception ignored)
		{
			// best-effort cleanup
		}
	}

}
