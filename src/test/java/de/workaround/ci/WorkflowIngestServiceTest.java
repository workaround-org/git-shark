package de.workaround.ci;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
 * Workflow ingestion on push (issue #2, phase 1): a push carrying a workflow with an {@code on: push}
 * trigger materializes one {@link ActionRun} and its {@link ActionTask}s; unrelated triggers and
 * pushes without workflows create nothing.
 */
@QuarkusTest
class WorkflowIngestServiceTest
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
	void pushWithOnPushWorkflowCreatesRunAndTask() throws Exception
	{
		User owner = persistUser("wf-alice-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = repositories.create(owner, "wf", Repository.Visibility.PUBLIC, null);

		String yaml = """
			name: CI
			on: push
			jobs:
			  build:
			    runs-on: ubuntu-latest
			    steps:
			      - run: echo hi
			""";
		ObjectId head = pushWorkflows(repo, Map.of(".forgejo/workflows/ci.yml", yaml));

		List<ActionRun> created = runs.findByRepository(repo);
		assertEquals(1, created.size());
		ActionRun run = created.get(0);
		assertEquals(1, run.number);
		assertEquals("push", run.event);
		assertEquals("refs/heads/main", run.ref);
		assertEquals(head.name(), run.commitSha);
		assertEquals("CI", run.workflowName);
		assertEquals(".forgejo/workflows/ci.yml", run.workflowFile);
		assertEquals(ActionRun.Status.PENDING, run.status);
		assertEquals(owner.id, run.triggeredBy.id);

		List<ActionTask> jobs = tasks.findByRun(run);
		assertEquals(1, jobs.size());
		assertEquals("build", jobs.get(0).name);
		assertEquals(ActionRun.Status.PENDING, jobs.get(0).status);
		assertEquals("ubuntu-latest", jobs.get(0).runsOn, "string runs-on is stored verbatim");
	}

	@Test
	void parsesRunsOnStringListAndAbsent() throws Exception
	{
		User owner = persistUser("wf-dave-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = repositories.create(owner, "wf", Repository.Visibility.PUBLIC, null);

		String yaml = """
			on: push
			jobs:
			  single:
			    runs-on: ubuntu-latest
			    steps: [{ run: echo hi }]
			  multi:
			    runs-on: [self-hosted, linux]
			    steps: [{ run: echo hi }]
			  anywhere:
			    steps: [{ run: echo hi }]
			""";
		pushWorkflows(repo, Map.of(".forgejo/workflows/ci.yml", yaml));

		ActionRun run = runs.findByRepository(repo).get(0);
		java.util.Map<String, String> byJob = tasks.findByRun(run).stream()
			.collect(java.util.stream.Collectors.toMap(t -> t.name, t -> t.runsOn));
		assertEquals("ubuntu-latest", byJob.get("single"));
		assertEquals("self-hosted,linux", byJob.get("multi"), "list runs-on is comma-joined");
		assertEquals("", byJob.get("anywhere"), "absent runs-on means no constraint");
	}

	@Test
	void pushWithNonPushTriggerCreatesNothing() throws Exception
	{
		User owner = persistUser("wf-bob-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = repositories.create(owner, "wf", Repository.Visibility.PUBLIC, null);

		String yaml = """
			name: PR only
			on: pull_request
			jobs:
			  test:
			    runs-on: ubuntu-latest
			""";
		pushWorkflows(repo, Map.of(".forgejo/workflows/pr.yml", yaml));

		assertTrue(runs.findByRepository(repo).isEmpty());
	}

	@Test
	void pushWithoutWorkflowsCreatesNothing() throws Exception
	{
		User owner = persistUser("wf-carol-" + UUID.randomUUID().toString().substring(0, 8));
		Repository repo = repositories.create(owner, "wf", Repository.Visibility.PUBLIC, null);

		pushWorkflows(repo, Map.of("README.md", "# hi\n"));

		assertTrue(runs.findByRepository(repo).isEmpty());
	}

	private ObjectId pushWorkflows(Repository repo, Map<String, String> files) throws Exception
	{
		Path bare = repositories.repositoryPath(repo);
		Map<String, byte[]> bytes = files.entrySet().stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
				e -> e.getValue().getBytes(StandardCharsets.UTF_8)));
		GitTestSeeder.seed(bare, bytes);

		try (org.eclipse.jgit.lib.Repository db = new FileRepositoryBuilder().setGitDir(bare.toFile()).build())
		{
			ObjectId head = db.resolve("refs/heads/main");
			ReceiveCommand command = new ReceiveCommand(ObjectId.zeroId(), head, "refs/heads/main");
			command.setResult(ReceiveCommand.Result.OK);
			ingest.onPush(repo.ownerHandle(), repo.name, repo.ownerUser.id, db, List.of(command));
			return head;
		}
	}

	@Transactional
	User persistUser(String name)
	{
		User user = new User();
		user.oidcSub = name;
		user.username = name;
		user.persist();
		return user;
	}

}
