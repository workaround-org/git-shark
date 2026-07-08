package de.workaround.web;

import de.workaround.git.InvalidMergeRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps the domain {@link InvalidMergeRequestException} to HTTP 400, so invalid merge-request input or state
 * (blank title, unknown branch, merge conflict) surfaces as a clean bad request instead of a generic 500.
 * Mirrors {@link InvalidIssueExceptionMapper}.
 */
@Provider
public class InvalidMergeRequestExceptionMapper implements ExceptionMapper<InvalidMergeRequestException>
{
	@Override
	public Response toResponse(InvalidMergeRequestException exception)
	{
		return Response.status(Response.Status.BAD_REQUEST)
			.entity(exception.getMessage())
			.type(MediaType.TEXT_PLAIN)
			.build();
	}
}
