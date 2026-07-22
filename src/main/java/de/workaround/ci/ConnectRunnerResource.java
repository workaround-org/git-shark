package de.workaround.ci;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import de.workaround.ci.proto.ping.v1.PingRequest;
import de.workaround.ci.proto.ping.v1.PingResponse;
import de.workaround.ci.proto.runner.v1.DeclareRequest;
import de.workaround.ci.proto.runner.v1.DeclareResponse;
import de.workaround.ci.proto.runner.v1.FetchTaskRequest;
import de.workaround.ci.proto.runner.v1.FetchTaskResponse;
import de.workaround.ci.proto.runner.v1.RegisterRequest;
import de.workaround.ci.proto.runner.v1.RegisterResponse;
import de.workaround.ci.proto.runner.v1.Runner;
import de.workaround.ci.proto.runner.v1.RunnerStatus;
import de.workaround.ci.proto.runner.v1.Task;
import de.workaround.ci.proto.runner.v1.TaskState;
import de.workaround.ci.proto.runner.v1.UpdateLogRequest;
import de.workaround.ci.proto.runner.v1.UpdateLogResponse;
import de.workaround.ci.proto.runner.v1.UpdateTaskRequest;
import de.workaround.ci.proto.runner.v1.UpdateTaskResponse;
import de.workaround.model.ActionRun;
import de.workaround.model.ActionTask;
import de.workaround.model.CiRunner;
import de.workaround.model.Repository;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;

/**
 * Server side of the Forgejo/Gitea <code>runner.v1</code> Connect protocol (phase 1: Ping, Register,
 * Declare). Connect unary RPC is a plain HTTP POST to <code>/{package}.{Service}/{Method}</code>
 * whose body is the serialized request message and whose 200 response body is the serialized
 * response message ({@code application/proto}). Stock <code>forgejo-runner</code> / <code>act_runner</code>
 * point their <code>instance</code> at this origin and reach these paths under <code>/api/actions</code>.
 * <p>
 * Errors follow the Connect wire format: a non-200 status with a small JSON body
 * <code>{"code": "...", "message": "..."}</code>. Post-registration calls authenticate with the
 * <code>x-runner-uuid</code> / <code>x-runner-token</code> headers the runner stores after Register.
 */
@Path("/api/actions")
@Consumes(ConnectRunnerResource.PROTO)
@Produces(ConnectRunnerResource.PROTO)
public class ConnectRunnerResource
{
	static final String PROTO = "application/proto";

	@Inject
	RunnerRegistrationService runnerService;

	@Inject
	TaskDispatchService dispatchService;

	@Inject
	TaskProgressService progressService;

	@POST
	@Path("/ping.v1.PingService/Ping")
	public Response ping(byte[] body) throws InvalidProtocolBufferException
	{
		PingRequest request = PingRequest.parseFrom(body);
		PingResponse response = PingResponse.newBuilder()
			.setData("Hello, " + request.getData())
			.build();
		return ok(response.toByteArray());
	}

	@POST
	@Path("/runner.v1.RunnerService/Register")
	public Response register(byte[] body) throws InvalidProtocolBufferException
	{
		RegisterRequest request = RegisterRequest.parseFrom(body);
		try
		{
			RunnerRegistrationService.RegisteredRunner reg = runnerService.register(request.getToken(),
				request.getName(), request.getLabelsList(), request.getVersion(), request.getEphemeral());
			Runner runner = toProto(reg.runner(), reg.plaintext());
			return ok(RegisterResponse.newBuilder().setRunner(runner).build().toByteArray());
		}
		catch (InvalidRegistrationTokenException e)
		{
			return connectError(Response.Status.UNAUTHORIZED, "unauthenticated", e.getMessage());
		}
	}

	@POST
	@Path("/runner.v1.RunnerService/Declare")
	public Response declare(@HeaderParam("x-runner-uuid") String uuid, @HeaderParam("x-runner-token") String token,
		byte[] body) throws InvalidProtocolBufferException
	{
		DeclareRequest request = DeclareRequest.parseFrom(body);
		try
		{
			CiRunner runner = runnerService.declare(uuid, token, request.getVersion(), request.getLabelsList());
			return ok(DeclareResponse.newBuilder().setRunner(toProto(runner, null)).build().toByteArray());
		}
		catch (RunnerAuthenticationException e)
		{
			return connectError(Response.Status.UNAUTHORIZED, "unauthenticated", e.getMessage());
		}
	}

	@POST
	@Path("/runner.v1.RunnerService/FetchTask")
	public Response fetchTask(@HeaderParam("x-runner-uuid") String uuid, @HeaderParam("x-runner-token") String token,
		byte[] body) throws InvalidProtocolBufferException
	{
		FetchTaskRequest.parseFrom(body); // tasks_version is advisory; phase 1 always checks the queue
		try
		{
			TaskDispatchService.Fetched fetched = dispatchService.fetch(uuid, token);
			FetchTaskResponse.Builder response = FetchTaskResponse.newBuilder()
				.setTasksVersion(fetched.tasksVersion());
			fetched.task().ifPresent(task -> response.setTask(toProto(task, fetched.secrets(), fetched.vars())));
			return ok(response.build().toByteArray());
		}
		catch (RunnerAuthenticationException e)
		{
			return connectError(Response.Status.UNAUTHORIZED, "unauthenticated", e.getMessage());
		}
	}

	@POST
	@Path("/runner.v1.RunnerService/UpdateTask")
	public Response updateTask(@HeaderParam("x-runner-uuid") String uuid, @HeaderParam("x-runner-token") String token,
		byte[] body) throws InvalidProtocolBufferException
	{
		UpdateTaskRequest request = UpdateTaskRequest.parseFrom(body);
		TaskState state = request.getState();
		Instant stoppedAt = state.hasStoppedAt()
			? Instant.ofEpochSecond(state.getStoppedAt().getSeconds(), state.getStoppedAt().getNanos())
			: null;
		try
		{
			progressService.updateTask(uuid, token, state.getId(), state.getResult(), stoppedAt);
			return ok(UpdateTaskResponse.newBuilder().setState(state).build().toByteArray());
		}
		catch (RunnerAuthenticationException e)
		{
			return connectError(Response.Status.UNAUTHORIZED, "unauthenticated", e.getMessage());
		}
		catch (TaskNotFoundException e)
		{
			return connectError(Response.Status.NOT_FOUND, "not_found", e.getMessage());
		}
	}

	@POST
	@Path("/runner.v1.RunnerService/UpdateLog")
	public Response updateLog(@HeaderParam("x-runner-uuid") String uuid, @HeaderParam("x-runner-token") String token,
		byte[] body) throws InvalidProtocolBufferException
	{
		UpdateLogRequest request = UpdateLogRequest.parseFrom(body);
		try
		{
			long ackIndex = progressService.appendLog(uuid, token, request.getTaskId(), request.getIndex(),
				request.getRowsList());
			return ok(UpdateLogResponse.newBuilder().setAckIndex(ackIndex).build().toByteArray());
		}
		catch (RunnerAuthenticationException e)
		{
			return connectError(Response.Status.UNAUTHORIZED, "unauthenticated", e.getMessage());
		}
		catch (TaskNotFoundException e)
		{
			return connectError(Response.Status.NOT_FOUND, "not_found", e.getMessage());
		}
	}

	private static Task toProto(ActionTask task, Map<String, String> secrets, Map<String, String> vars)
	{
		Task.Builder builder = Task.newBuilder().setId(task.seq);
		if (task.payload != null)
		{
			builder.setWorkflowPayload(ByteString.copyFromUtf8(task.payload));
		}
		builder.setContext(githubContext(task));
		builder.putAllSecrets(secrets);
		builder.putAllVars(vars);
		return builder.build();
	}

	/**
	 * The GitHub-Actions {@code github.*} context the runner needs to execute the task. Without it the
	 * runner cannot pick the job from the workflow ({@code github.job}) and dereferences a nil context.
	 * Phase 1 supplies the identity/ref fields a plain {@code run:} job needs; richer fields (tokens,
	 * event payloads) arrive with secrets/variables support.
	 */
	private static Struct githubContext(ActionTask task)
	{
		ActionRun run = task.run;
		Repository repo = run.repository;
		String prefix = "refs/heads/";
		String refName = run.ref.startsWith(prefix) ? run.ref.substring(prefix.length()) : run.ref;
		String fullName = repo.ownerHandle() + "/" + repo.name;
		Struct.Builder context = Struct.newBuilder();
		putString(context, "token", "");
		putString(context, "actor", run.triggeredBy != null ? run.triggeredBy.username : "ghost");
		putString(context, "run_id", String.valueOf(task.seq));
		putString(context, "run_number", String.valueOf(run.number));
		putString(context, "run_attempt", "1");
		putString(context, "job", task.name);
		putString(context, "ref", run.ref);
		putString(context, "ref_name", refName);
		putString(context, "ref_type", "branch");
		putString(context, "sha", run.commitSha);
		putString(context, "repository", fullName);
		putString(context, "repository_owner", repo.ownerHandle());
		putString(context, "event_name", run.event);
		context.putFields("event", Value.newBuilder().setStructValue(Struct.getDefaultInstance()).build());
		return context.build();
	}

	private static void putString(Struct.Builder context, String key, String value)
	{
		context.putFields(key, Value.newBuilder().setStringValue(value == null ? "" : value).build());
	}

	private static Runner toProto(CiRunner runner, String plaintextSecret)
	{
		Runner.Builder builder = Runner.newBuilder()
			.setUuid(runner.uuid)
			.setName(runner.name)
			.setStatus(toProtoStatus(runner.status))
			.setEphemeral(runner.ephemeral)
			.addAllLabels(splitLabels(runner.labels));
		if (runner.version != null)
		{
			builder.setVersion(runner.version);
		}
		// The runner secret is delivered only in the Register response; Declare must not resend it.
		if (plaintextSecret != null)
		{
			builder.setToken(plaintextSecret);
		}
		return builder.build();
	}

	private static RunnerStatus toProtoStatus(CiRunner.Status status)
	{
		return switch (status)
		{
			case IDLE -> RunnerStatus.RUNNER_STATUS_IDLE;
			case ACTIVE -> RunnerStatus.RUNNER_STATUS_ACTIVE;
			case OFFLINE -> RunnerStatus.RUNNER_STATUS_OFFLINE;
			case UNSPECIFIED -> RunnerStatus.RUNNER_STATUS_UNSPECIFIED;
		};
	}

	private static List<String> splitLabels(String labels)
	{
		if (labels == null || labels.isBlank())
		{
			return List.of();
		}
		return Arrays.stream(labels.split(",")).filter(s -> !s.isBlank()).toList();
	}

	private static Response ok(byte[] payload)
	{
		return Response.ok(payload, PROTO).build();
	}

	private static Response connectError(Response.Status status, String code, String message)
	{
		String json = "{\"code\":\"" + code + "\",\"message\":\"" + escapeJson(message) + "\"}";
		return Response.status(status).type("application/json").entity(json).build();
	}

	private static String escapeJson(String value)
	{
		return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

}
