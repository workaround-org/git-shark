package de.workaround.ci;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.google.protobuf.InvalidProtocolBufferException;

import de.workaround.ci.proto.ping.v1.PingRequest;
import de.workaround.ci.proto.ping.v1.PingResponse;
import de.workaround.ci.proto.runner.v1.DeclareRequest;
import de.workaround.ci.proto.runner.v1.DeclareResponse;
import de.workaround.ci.proto.runner.v1.RegisterRequest;
import de.workaround.ci.proto.runner.v1.RegisterResponse;
import de.workaround.ci.proto.runner.v1.RunnerStatus;
import de.workaround.model.User;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the runner.v1 Connect endpoints exactly as a stock forgejo-runner would: serialized
 * protobuf over HTTP POST to {@code /api/actions/...}. A real runner binary round-trip is deferred
 * to a later phase; here a hand-built protobuf client stands in for it.
 */
@QuarkusTest
class ConnectRunnerResourceTest
{
	private static final String PROTO = "application/proto";

	@Inject
	RunnerRegistrationService runnerService;

	@Inject
	User.Repo userRepo;

	@Test
	void pingIsAnswered() throws InvalidProtocolBufferException
	{
		byte[] responseBytes = given()
			.contentType(PROTO)
			.body(PingRequest.newBuilder().setData("shark").build().toByteArray())
			.when().post("/api/actions/ping.v1.PingService/Ping")
			.then().statusCode(200)
			.extract().asByteArray();

		PingResponse response = PingResponse.parseFrom(responseBytes);
		assertEquals("Hello, shark", response.getData());
	}

	@Test
	void registerThenDeclareOverConnectRpc() throws InvalidProtocolBufferException
	{
		String regToken = mintRegistrationToken();

		byte[] registerBytes = given()
			.contentType(PROTO)
			.body(RegisterRequest.newBuilder()
				.setToken(regToken)
				.setName("connect-runner")
				.addLabels("ubuntu-latest")
				.setVersion("v4.0.0")
				.build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/Register")
			.then().statusCode(200)
			.extract().asByteArray();

		RegisterResponse register = RegisterResponse.parseFrom(registerBytes);
		assertFalse(register.getRunner().getUuid().isBlank(), "runner assigned a uuid");
		assertFalse(register.getRunner().getToken().isBlank(), "runner secret delivered once");
		assertEquals(RunnerStatus.RUNNER_STATUS_IDLE, register.getRunner().getStatus());
		assertEquals(List.of("ubuntu-latest"), register.getRunner().getLabelsList());

		String uuid = register.getRunner().getUuid();
		String secret = register.getRunner().getToken();

		byte[] declareBytes = given()
			.contentType(PROTO)
			.header("x-runner-uuid", uuid)
			.header("x-runner-token", secret)
			.body(DeclareRequest.newBuilder()
				.setVersion("v4.1.0")
				.addLabels("ubuntu-latest")
				.addLabels("arm64")
				.build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/Declare")
			.then().statusCode(200)
			.extract().asByteArray();

		DeclareResponse declare = DeclareResponse.parseFrom(declareBytes);
		assertEquals("v4.1.0", declare.getRunner().getVersion());
		assertEquals(List.of("ubuntu-latest", "arm64"), declare.getRunner().getLabelsList());
		assertTrue(declare.getRunner().getToken().isEmpty(), "Declare must not resend the secret");
	}

	@Test
	void registerWithBadTokenReturnsUnauthenticated()
	{
		given()
			.contentType(PROTO)
			.body(RegisterRequest.newBuilder().setToken("gsr_bogus").setName("x").build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/Register")
			.then().statusCode(401);
	}

	@Test
	void declareWithBadTokenReturnsUnauthenticated()
	{
		given()
			.contentType(PROTO)
			.header("x-runner-uuid", "does-not-exist")
			.header("x-runner-token", "gsrt_bogus")
			.body(DeclareRequest.newBuilder().setVersion("v1").build().toByteArray())
			.when().post("/api/actions/runner.v1.RunnerService/Declare")
			.then().statusCode(401);
	}

	private String mintRegistrationToken()
	{
		return runnerService.createRegistrationToken(persistAdmin()).plaintext();
	}

	@Transactional
	User persistAdmin()
	{
		String name = "runner-admin-" + UUID.randomUUID();
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
