package de.workaround.dev;

import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.transport.RefSpec;
import org.jboss.logging.Logger;

import de.workaround.git.GitRepositoryService;
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

	@Inject
	GitRepositoryService repositories;

	@Inject
	User.Repo users;

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

	private static void seedInitialCommit(Path barePath)
	{
		Path work = null;
		try
		{
			work = Files.createTempDirectory("git-shark-seed");
			try (Git git = Git.cloneRepository().setURI(barePath.toUri().toString()).setDirectory(work.toFile()).call())
			{
				Files.writeString(work.resolve("README.md"),
					"# demo\n\nDemo repository seeded for local development.\n");
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
