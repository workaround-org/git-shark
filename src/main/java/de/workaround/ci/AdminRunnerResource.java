package de.workaround.ci;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import de.workaround.account.AdminAccess;
import de.workaround.account.CurrentUser;
import de.workaround.model.CiRunner;
import de.workaround.model.CiRunnerRegistrationToken;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Instance-admin UI for CI/CD runners: generate registration tokens, list registered runners and
 * their presence, and delete either. Every handler is gated by {@link AdminAccess}; the routes are
 * additionally behind the {@code /admin/*} authenticated policy so anonymous requests never reach here.
 */
@Path("/admin/runners")
@Produces(MediaType.TEXT_HTML)
public class AdminRunnerResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance runners(List<CiRunner> runners, List<CiRunnerRegistrationToken> tokens);

		static native TemplateInstance tokenCreated(String plaintext);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	AdminAccess adminAccess;

	@Inject
	RunnerRegistrationService runnerService;

	@GET
	public TemplateInstance list()
	{
		requireAdmin();
		return Templates.runners(runnerService.list(), runnerService.listRegistrationTokens());
	}

	@POST
	@Path("/tokens")
	public TemplateInstance createToken()
	{
		requireAdmin();
		RunnerRegistrationService.CreatedRegistrationToken created =
			runnerService.createRegistrationToken(currentUser.require());
		return Templates.tokenCreated(created.plaintext());
	}

	@POST
	@Path("/tokens/{id}/delete")
	public Response deleteToken(@PathParam("id") UUID id)
	{
		requireAdmin();
		runnerService.deleteRegistrationToken(id);
		return Response.seeOther(URI.create("/admin/runners")).build();
	}

	@POST
	@Path("/{id}/delete")
	public Response deleteRunner(@PathParam("id") UUID id)
	{
		requireAdmin();
		runnerService.delete(id);
		return Response.seeOther(URI.create("/admin/runners")).build();
	}

	private void requireAdmin()
	{
		adminAccess.require(currentUser.require());
	}

}
