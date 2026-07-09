package de.workaround.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import de.workaround.account.CurrentUser;
import de.workaround.git.AccessPolicy;
import de.workaround.git.GitRepositoryService;
import de.workaround.git.IssueService;
import de.workaround.model.Issue;
import de.workaround.model.Repository;
import de.workaround.model.User;
import io.quarkus.qute.CheckedTemplate;
import io.quarkus.qute.TemplateInstance;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@jakarta.ws.rs.Path("/repos/{owner}/{name}/issues")
@Produces(MediaType.TEXT_HTML)
public class IssueResource
{
	@CheckedTemplate
	static class Templates
	{
		static native TemplateInstance issues(Repository repo, RepoNav nav, boolean owner, List<Issue> open,
			List<Issue> done);

		static native TemplateInstance newIssue(Repository repo, RepoNav nav);

		static native TemplateInstance issue(Repository repo, RepoNav nav, boolean owner, Issue issue,
			List<Issue.Status> statuses);
	}

	@Inject
	CurrentUser currentUser;

	@Inject
	GitRepositoryService service;

	@Inject
	AccessPolicy accessPolicy;

	@Inject
	IssueService issueService;

	@Inject
	RepoNavService repoNav;

	@Context
	UriInfo uriInfo;

	@GET
	public TemplateInstance list(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		User user = currentUser.get();
		boolean isOwner = user != null && user.id.equals(repo.owner.id);
		List<Issue> all = issueService.list(repo);
		// open issues stay visible; DONE issues are tucked into a collapsible archive on the page
		List<Issue> open = all.stream().filter(issue -> issue.status != Issue.Status.DONE).toList();
		List<Issue> done = all.stream().filter(issue -> issue.status == Issue.Status.DONE).toList();
		return Templates.issues(repo, repoNav.build(repo, uriInfo), isOwner, open, done);
	}

	@GET
	@jakarta.ws.rs.Path("new")
	public TemplateInstance newForm(@PathParam("owner") String owner, @PathParam("name") String name)
	{
		Repository repo = requireReadable(owner, name);
		// only users with write access (owner or collaborator) may open an issue
		if (!accessPolicy.canWrite(currentUser.get(), repo))
		{
			throw new de.workaround.git.ForbiddenOperationException("Only the repository owner or a collaborator can open issues");
		}
		return Templates.newIssue(repo, repoNav.build(repo, uriInfo));
	}

	@POST
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response create(@PathParam("owner") String owner, @PathParam("name") String name,
		@FormParam("title") String title, @FormParam("description") String description)
	{
		Repository repo = requireReadable(owner, name);
		// title validation lives in IssueService (InvalidIssueException -> 400 via InvalidIssueExceptionMapper)
		Issue issue = issueService.create(currentUser.require(), repo, title, description);
		return Response.seeOther(issueUri(repo, issue.id)).build();
	}

	@GET
	@jakarta.ws.rs.Path("{id}")
	public TemplateInstance detail(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") String id)
	{
		Repository repo = requireReadable(owner, name);
		Issue issue = issueService.find(repo, parseId(id)).orElseThrow(NotFoundException::new);
		User user = currentUser.get();
		boolean isOwner = user != null && user.id.equals(repo.owner.id);
		return Templates.issue(repo, repoNav.build(repo, uriInfo), isOwner, issue, List.of(Issue.Status.values()));
	}

	@POST
	@jakarta.ws.rs.Path("{id}/status")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response updateStatus(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") String id, @FormParam("status") String status)
	{
		Repository repo = requireReadable(owner, name);
		Issue issue = issueService.find(repo, parseId(id)).orElseThrow(NotFoundException::new);
		issueService.updateStatus(currentUser.require(), issue, parseStatus(status));
		return Response.seeOther(issueUri(repo, issue.id)).build();
	}

	@POST
	@jakarta.ws.rs.Path("{id}/delete")
	@Consumes(MediaType.APPLICATION_FORM_URLENCODED)
	public Response delete(@PathParam("owner") String owner, @PathParam("name") String name,
		@PathParam("id") String id)
	{
		Repository repo = requireReadable(owner, name);
		Issue issue = issueService.find(repo, parseId(id)).orElseThrow(NotFoundException::new);
		issueService.delete(currentUser.require(), issue);
		return Response.seeOther(URI.create("/repos/" + repo.owner.username + "/" + repo.name + "/issues")).build();
	}

	private URI issueUri(Repository repo, UUID id)
	{
		return URI.create("/repos/" + repo.owner.username + "/" + repo.name + "/issues/" + id);
	}

	private static UUID parseId(String id)
	{
		try
		{
			return UUID.fromString(id);
		}
		catch (IllegalArgumentException malformed)
		{
			throw new NotFoundException();
		}
	}

	private static Issue.Status parseStatus(String status)
	{
		try
		{
			return Issue.Status.valueOf(status);
		}
		catch (IllegalArgumentException | NullPointerException invalid)
		{
			throw new BadRequestException("Unknown issue status: " + status);
		}
	}

	private Repository requireReadable(String owner, String name)
	{
		Repository repo = service.find(owner, name).orElseThrow(NotFoundException::new);
		if (!accessPolicy.canRead(currentUser.get(), repo))
		{
			// hide existence of private repositories
			throw new NotFoundException();
		}
		return repo;
	}

}
