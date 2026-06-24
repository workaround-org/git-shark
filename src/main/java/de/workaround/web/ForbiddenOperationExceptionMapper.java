package de.workaround.web;

import de.workaround.git.ForbiddenOperationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

/**
 * Maps the domain {@link ForbiddenOperationException} to HTTP 403 instead of a generic 500, so
 * authorization failures (e.g. an anonymous request attempting a mutation) surface cleanly.
 */
@Provider
public class ForbiddenOperationExceptionMapper implements ExceptionMapper<ForbiddenOperationException>
{
	@Override
	public Response toResponse(ForbiddenOperationException exception)
	{
		return Response.status(Response.Status.FORBIDDEN)
			.entity(exception.getMessage())
			.type(MediaType.TEXT_PLAIN)
			.build();
	}
}
