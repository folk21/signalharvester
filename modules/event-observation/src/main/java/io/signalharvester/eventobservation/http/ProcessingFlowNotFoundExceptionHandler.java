package io.signalharvester.eventobservation.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.eventobservation.application.ProcessingFlowNotFoundException;
import jakarta.inject.Singleton;

/** Maps missing retained flow scopes to HTTP 404 at the transport boundary. */
@Produces
@Singleton
public final class ProcessingFlowNotFoundExceptionHandler
        implements ExceptionHandler<ProcessingFlowNotFoundException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, ProcessingFlowNotFoundException exception) {
        return HttpResponse.notFound();
    }
}
