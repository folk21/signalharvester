package io.signalharvester.results.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.results.application.InvalidResultQueryException;
import jakarta.inject.Singleton;
import java.util.Map;

/** Maps invalid Results filters, search expressions, and browsing cursors to HTTP 400. */
@Produces
@Singleton
public final class InvalidResultQueryExceptionHandler
        implements ExceptionHandler<InvalidResultQueryException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, InvalidResultQueryException exception) {
        return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
    }
}
