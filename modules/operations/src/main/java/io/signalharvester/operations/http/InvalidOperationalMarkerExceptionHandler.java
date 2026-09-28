package io.signalharvester.operations.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import java.util.Map;

/** Maps rejected unsafe operational markers to HTTP 400 without persisting their values. */
@Produces
@Singleton
public final class InvalidOperationalMarkerExceptionHandler
        implements ExceptionHandler<InvalidOperationalMarkerException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, InvalidOperationalMarkerException exception) {
        return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
    }
}
