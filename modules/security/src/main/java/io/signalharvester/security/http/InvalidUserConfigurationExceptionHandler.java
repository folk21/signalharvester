package io.signalharvester.security.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.security.application.InvalidUserConfigurationException;
import jakarta.inject.Singleton;

/** Maps invalid account configuration to HTTP 400. */
@Produces
@Singleton
public final class InvalidUserConfigurationExceptionHandler
        implements ExceptionHandler<InvalidUserConfigurationException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, InvalidUserConfigurationException exception) {
        return HttpResponse.badRequest();
    }
}
