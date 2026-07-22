package de.workaround.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import de.workaround.account.CurrentUser;
import de.workaround.ci.ActionSecretService;
import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.model.ActionSecret;
import de.workaround.model.ActionVariable;
import de.workaround.model.Repository;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * Owner-only management of a repository's CI secrets and variables (issue #2, phase 2). Secret values
 * are write-only — never rendered back. Mirrors the settings idiom: resolve + admin-gate (404 to hide
 * private repos), POST then 303 back, re-render with an error message on validation failure.
 */
@jakarta.ws.rs.Path("/repos/{owner}/{name}/settings/actions")
@Produces(MediaType.TEXT_HTML)
public class ActionSettingsResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance settings(Repository repo, RepoNav nav, String error,
			List<ActionSecret> secrets, List<ActionVariable> variables);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService service;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	RepoNavService repoNav;

	@Inject
	ActionSecretService actionSecrets;

	@Context
	UriInfo uriInfo;

	@GET
	public TemplateInstance page(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireOwner(owner, name);
		return render(repo, null);
	}

	@POST
	@jakarta.ws.rs.Path("secrets")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response addSecret(@PathParam("owner") String owner, @PathParam("name") String name,
		@jakarta.ws.rs.FormParam("name") String secretName, @jakarta.ws.rs.FormParam("value") String value)
	{
		Repository repo = requireOwner(owner, name);
		try
		{
			actionSecrets.addSecret(repo, secretName, value);
			return backToSettings(repo);
		}
		catch (IllegalArgumentException | IllegalStateException e)
		{
			return Response.status(Response.Status.BAD_REQUEST).entity(render(repo, e.getMessage())).build();
		}
	}

	@POST
	@jakarta.ws.rs.Path("secrets/{id}/delete")
	public Response deleteSecret(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") UUID id)
	{
		Repository repo = requireOwner(owner, name);
		actionSecrets.deleteSecret(repo, id);
		return backToSettings(repo);
	}

	@POST
	@jakarta.ws.rs.Path("variables")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response addVariable(@PathParam("owner") String owner, @PathParam("name") String name,
		@jakarta.ws.rs.FormParam("name") String varName, @jakarta.ws.rs.FormParam("value") String value)
	{
		Repository repo = requireOwner(owner, name);
		try
		{
			actionSecrets.addVariable(repo, varName, value);
			return backToSettings(repo);
		}
		catch (IllegalArgumentException e)
		{
			return Response.status(Response.Status.BAD_REQUEST).entity(render(repo, e.getMessage())).build();
		}
	}

	@POST
	@jakarta.ws.rs.Path("variables/{id}/delete")
	public Response deleteVariable(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") UUID id)
	{
		Repository repo = requireOwner(owner, name);
		actionSecrets.deleteVariable(repo, id);
		return backToSettings(repo);
	}

	private TemplateInstance render(Repository repo, String error)
	{
		return Templates.settings(repo, repoNav.build(repo, uriInfo), error,
			actionSecrets.listSecrets(repo), actionSecrets.listVariables(repo));
	}

	private Response backToSettings(Repository repo)
	{
		return Response.seeOther(URI.create(
			"/repos/" + repo.ownerHandle() + "/" + repo.name + "/settings/actions")).build();
	}

	private Repository requireOwner(String owner, String name)
	{
		Repository repo = service.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canAdmin(currentUser.get(), repo))
		{
			throw new NotFoundException();
		}
		return repo;
	}
}
