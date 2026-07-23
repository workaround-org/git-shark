package de.workaround.ci;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.ReceiveCommand;
import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.git.GitTestSeeder;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Matrix expansion (issue #2, phase 2): a job with {@code strategy.matrix} becomes one task per
 * combination, each with a single-cell payload and the shared job id.
 */
@QuarkusTest
class MatrixExpansionTest
{
	@Inject
	WorkflowIngestService ingest;

	@Inject
	GitRepositoryService repositories;

	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Test
	void singleDimensionMatrixExpandsToOneTaskPerValue()
	{
		String yaml = """
			on: push
			jobs:
			  build:
			    runs-on: ubuntu-latest
			    strategy:
			      matrix:
			        os: [linux, windows]
			    steps:
			      - run: echo hi
			""";
		List<ActionTask> jobs = ingestAndList("mx-a", yaml);

		assertEquals(2, jobs.size());
		Set<String> names = jobs.stream().map(t -> t.name).collect(Collectors.toSet());
		assertEquals(Set.of("build (linux)", "build (windows)"), names);
		assertTrue(jobs.stream().allMatch(t -> t.jobId.equals("build")), "cells share the job id");

		ActionTask linux = jobs.stream().filter(t -> t.name.equals("build (linux)")).findFirst().orElseThrow();
		assertTrue(linux.payload.contains("linux"), "cell payload carries its matrix value");
		assertTrue(!linux.payload.contains("windows"), "cell payload is reduced to its own value");
	}

	@Test
	void twoDimensionMatrixExpandsToCartesianProduct()
	{
		String yaml = """
			on: push
			jobs:
			  test:
			    runs-on: ubuntu-latest
			    strategy:
			      matrix:
			        os: [linux, windows]
			        jdk: [17, 21]
			    steps:
			      - run: echo hi
			""";
		List<ActionTask> jobs = ingestAndList("mx-b", yaml);

		assertEquals(4, jobs.size(), "2 x 2 = 4 cells");
		assertTrue(jobs.stream().allMatch(t -> t.jobId.equals("test")));
		assertTrue(jobs.stream().map(t -> t.name).allMatch(n -> n.startsWith("test (")));
	}

	@Test
	void nonMatrixJobStaysSingle()
	{
		String yaml = "on: push\njobs:\n  build:\n    runs-on: ubuntu-latest\n    steps:\n      - run: echo hi\n";
		List<ActionTask> jobs = ingestAndList("mx-c", yaml);

		assertEquals(1, jobs.size());
		assertEquals("build", jobs.get(0).name);
		assertEquals("build", jobs.get(0).jobId);
	}

	private List<ActionTask> ingestAndList(String repoName, String yaml)
	{
		Repository repo = seedAndIngest(repoName, yaml);
		return tasks.findByRun(runs.findByRepository(repo).get(0));
	}

	@Transactional
	Repository seedAndIngest(String repoName, String yaml)
	{
		String username = repoName + "-" + UUID.randomUUID().toString().substring(0, 8);
		User owner = new User();
		owner.oidcSub = username;
		owner.username = username;
		owner.persist();
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);

		try
		{
			Path bare = repositories.repositoryPath(repo);
			GitTestSeeder.seed(bare, Map.of(".forgejo/workflows/ci.yml", yaml.getBytes(StandardCharsets.UTF_8)));
			try (org.eclipse.jgit.lib.Repository db = new FileRepositoryBuilder().setGitDir(bare.toFile()).build())
			{
				ObjectId head = db.resolve("refs/heads/main");
				ReceiveCommand command = new ReceiveCommand(ObjectId.zeroId(), head, "refs/heads/main");
				command.setResult(ReceiveCommand.Result.OK);
				ingest.onPush(repo.ownerHandle(), repo.name, owner.id, db, List.of(command));
			}
		}
		catch (Exception e)
		{
			throw new RuntimeException(e);
		}
		return repo;
	}
}
