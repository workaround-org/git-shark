package de.workaround.ci;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.git.GitRepositoryService;
import de.workaround.mirror.SecretCrypto;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionSecret;
import de.workaround.model.ActionTask;
import de.workaround.model.ActionVariable;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Secret and variable delivery (issue #2, phase 2): a claimed task is handed its repository's
 * variables (plaintext) and secrets (decrypted from storage) in the FetchTask response.
 */
@QuarkusTest
class SecretDeliveryTest
{
	@Inject
	RunnerRegistrationService runnerService;

	@Inject
	TaskDispatchService dispatch;

	@Inject
	GitRepositoryService repositories;

	@Inject
	SecretCrypto crypto;

	@Inject
	ActionRun.Repo runs;

	@Inject
	ActionTask.Repo tasks;

	@Inject
	ActionSecret.Repo secrets;

	@Inject
	ActionVariable.Repo variables;

	@Test
	void claimedTaskReceivesRepoSecretsAndVariables()
	{
		Repository repo = seed("sd-a");
		RunnerRegistrationService.RegisteredRunner reg = registerRunner(repo);

		TaskDispatchService.Fetched fetched = dispatch.fetch(reg.runner().uuid, reg.plaintext());

		assertTrue(fetched.task().isPresent());
		assertEquals("s3cr3t", fetched.secrets().get("API_TOKEN"), "secret delivered decrypted");
		assertEquals("production", fetched.vars().get("DEPLOY_ENV"), "variable delivered as-is");
	}

	@Test
	void undecryptableSecretIsDroppedNotLeaked()
	{
		Repository repo = seedWithCorruptSecret("sd-c");
		RunnerRegistrationService.RegisteredRunner reg = registerRunner(repo);

		TaskDispatchService.Fetched fetched = dispatch.fetch(reg.runner().uuid, reg.plaintext());

		assertTrue(fetched.task().isPresent());
		assertEquals("s3cr3t", fetched.secrets().get("API_TOKEN"), "valid secret still delivered");
		assertTrue(fetched.secrets().containsKey("API_TOKEN"));
		assertTrue(!fetched.secrets().containsKey("BROKEN"), "undecryptable secret is dropped, never leaked");
	}

	@Test
	void emptyFetchCarriesNoSecrets()
	{
		Repository repo = emptyRepo("sd-b");
		RunnerRegistrationService.RegisteredRunner reg = registerRunner(repo);

		TaskDispatchService.Fetched fetched = dispatch.fetch(reg.runner().uuid, reg.plaintext());

		assertTrue(fetched.task().isEmpty());
		assertTrue(fetched.secrets().isEmpty());
		assertTrue(fetched.vars().isEmpty());
	}

	// scope the runner to the test's own repo so the global dispatch queue (other tests' committed
	// pending tasks) can't be claimed instead
	private RunnerRegistrationService.RegisteredRunner registerRunner(Repository repo)
	{
		String token = runnerService.createRegistrationToken(persistUser("sd-admin-" + shortId()), repo).plaintext();
		return runnerService.register(token, "sd-runner", List.of(), "v4.0.0", false);
	}

	@Transactional
	Repository emptyRepo(String repoName)
	{
		return repositories.create(persistUser(repoName + "-" + shortId()), repoName, Repository.Visibility.PUBLIC,
			null);
	}

	@Transactional
	Repository seed(String repoName)
	{
		User owner = persistUser(repoName + "-" + shortId());
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);

		ActionSecret secret = new ActionSecret();
		secret.repository = repo;
		secret.name = "API_TOKEN";
		secret.valueEncrypted = crypto.encrypt("s3cr3t");
		secret.persist();

		ActionVariable variable = new ActionVariable();
		variable.repository = repo;
		variable.name = "DEPLOY_ENV";
		variable.value = "production";
		variable.persist();

		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = runs.maxNumber(repo) + 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.persist();

		ActionTask task = new ActionTask();
		task.run = run;
		task.name = "build";
		task.payload = "on: push";
		task.persist();
		return repo;
	}

	@Transactional
	Repository seedWithCorruptSecret(String repoName)
	{
		User owner = persistUser(repoName + "-" + shortId());
		Repository repo = repositories.create(owner, repoName, Repository.Visibility.PUBLIC, null);

		ActionSecret good = new ActionSecret();
		good.repository = repo;
		good.name = "API_TOKEN";
		good.valueEncrypted = crypto.encrypt("s3cr3t");
		good.persist();

		ActionSecret broken = new ActionSecret();
		broken.repository = repo;
		broken.name = "BROKEN";
		broken.valueEncrypted = "enc1:not-valid-ciphertext";
		broken.persist();

		ActionRun run = new ActionRun();
		run.repository = repo;
		run.number = runs.maxNumber(repo) + 1;
		run.workflowName = "CI";
		run.workflowFile = ".forgejo/workflows/ci.yml";
		run.event = "push";
		run.ref = "refs/heads/main";
		run.commitSha = "0000000000000000000000000000000000000000";
		run.persist();

		ActionTask task = new ActionTask();
		task.run = run;
		task.name = "build";
		task.payload = "on: push";
		task.persist();
		return repo;
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

	private static String shortId()
	{
		return UUID.randomUUID().toString().substring(0, 8);
	}
}
