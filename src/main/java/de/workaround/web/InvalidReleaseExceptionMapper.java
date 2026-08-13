package de.workaround.web;

import de.workaround.git.InvalidReleaseException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps the domain {@link InvalidReleaseException} to HTTP 400, so invalid release input (a blank tag name, an
 * unknown target, a tag that already has a release) surfaces as a clean bad request instead of a generic 500.
 * Mirrors {@link InvalidIssueExceptionMapper}.
 */
@Provider
public class InvalidReleaseExceptionMapper implements ExceptionMapper<InvalidReleaseException>
{
	@Override
	public Response toResponse(InvalidReleaseException exception)
	{
		return Response.status(Response.Status.BAD_REQUEST)
			.entity(exception.getMessage())
			.type(MediaType.TEXT_PLAIN)
			.build();
	}
}
