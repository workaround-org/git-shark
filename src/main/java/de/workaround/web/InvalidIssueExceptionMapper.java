package de.workaround.web;

import de.workaround.git.InvalidIssueException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps the domain {@link InvalidIssueException} to HTTP 400, so invalid issue input (e.g. a blank
 * title) surfaces as a clean bad request instead of a generic 500. Mirrors
 * {@link ForbiddenOperationExceptionMapper}.
 */
@Provider
public class InvalidIssueExceptionMapper implements ExceptionMapper<InvalidIssueException>
{
	@Override
	public Response toResponse(InvalidIssueException exception)
	{
		return Response.status(Response.Status.BAD_REQUEST)
			.entity(exception.getMessage())
			.type(MediaType.TEXT_PLAIN)
			.build();
	}
}
