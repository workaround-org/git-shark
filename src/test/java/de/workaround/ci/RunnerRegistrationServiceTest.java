package de.workaround.ci;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.workaround.model.CiRunner;
import de.workaround.model.User;
import io.quarkus.test.TestTransaction;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

@QuarkusTest
class RunnerRegistrationServiceTest
{
	@Inject
	RunnerRegistrationService service;

	@Inject
	CiRunner.Repo runners;

	@Test
	@TestTransaction
	void registrationTokenReturnsPlaintextOnceAndStoresOnlyHash()
	{
		User admin = persistUser();

		RunnerRegistrationService.CreatedRegistrationToken created = service.createRegistrationToken(admin);

		assertNotNull(created.plaintext());
		assertNotEquals(created.plaintext(), created.token().tokenHash, "plaintext must never be persisted");
		assertEquals(service.hash(created.plaintext()), created.token().tokenHash);
	}

	@Test
	@TestTransaction
	void registerWithValidTokenCreatesIdleRunnerAndReturnsSecretOnce()
	{
		User admin = persistUser();
		String regToken = service.createRegistrationToken(admin).plaintext();

		RunnerRegistrationService.RegisteredRunner reg =
			service.register(regToken, "builder-1", List.of("ubuntu-latest", "docker"), "v4.0.0", false);

		CiRunner runner = reg.runner();
		assertEquals(CiRunner.Status.IDLE, runner.status);
		assertEquals("builder-1", runner.name);
		assertEquals("ubuntu-latest,docker", runner.labels);
		assertNotNull(runner.uuid);
		assertNotNull(reg.plaintext());
		assertNotEquals(reg.plaintext(), runner.tokenHash, "runner secret plaintext must never be persisted");
		assertEquals(service.hash(reg.plaintext()), runner.tokenHash);
	}

	@Test
	@TestTransaction
	void registerWithUnknownTokenIsRejected()
	{
		assertThrows(InvalidRegistrationTokenException.class,
			() -> service.register("gsr_nope", "x", List.of(), "v1", false));
	}

	@Test
	@TestTransaction
	void declareWithValidCredentialsUpdatesVersionAndLabels()
	{
		User admin = persistUser();
		String regToken = service.createRegistrationToken(admin).plaintext();
		RunnerRegistrationService.RegisteredRunner reg = service.register(regToken, "b", List.of("old"), "v1", false);

		CiRunner declared = service.declare(reg.runner().uuid, reg.plaintext(), "v4.1.0", List.of("ubuntu", "arm64"));

		assertEquals("v4.1.0", declared.version);
		assertEquals("ubuntu,arm64", declared.labels);
		assertNotNull(declared.lastSeen);
	}

	@Test
	@TestTransaction
	void declareWithWrongTokenIsRejected()
	{
		User admin = persistUser();
		String regToken = service.createRegistrationToken(admin).plaintext();
		RunnerRegistrationService.RegisteredRunner reg = service.register(regToken, "b", List.of(), "v1", false);

		assertThrows(RunnerAuthenticationException.class,
			() -> service.declare(reg.runner().uuid, "gsrt_wrong", "v2", List.of()));
	}

	@Test
	@TestTransaction
	void deleteRemovesRunner()
	{
		User admin = persistUser();
		String regToken = service.createRegistrationToken(admin).plaintext();
		RunnerRegistrationService.RegisteredRunner reg = service.register(regToken, "b", List.of(), "v1", false);
		UUID id = reg.runner().id;

		service.delete(id);

		assertNull(runners.findById(id));
	}

	private static User persistUser()
	{
		String name = "admin-" + UUID.randomUUID();
		User user = new User();
		user.oidcSub = "sub-" + name;
		user.username = name;
		user.persist();
		return user;
	}

}
