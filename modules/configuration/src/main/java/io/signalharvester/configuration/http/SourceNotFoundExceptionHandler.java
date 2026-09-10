package io.signalharvester.configuration.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.configuration.application.SourceNotFoundException;
import jakarta.inject.Singleton;

/**
 * Maps missing configured sources to the REST contract's 404 response.
 */
@Produces
@Singleton
public final class SourceNotFoundExceptionHandler
        implements ExceptionHandler<SourceNotFoundException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, SourceNotFoundException exception) {
        return HttpResponse.notFound();
    }
}
