package io.signalharvester.configuration.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.configuration.application.InvalidSourceConfigurationException;
import jakarta.inject.Singleton;

/**
 * Maps invalid effective source configuration to the REST contract's 400 response.
 */
@Produces
@Singleton
public final class InvalidSourceConfigurationExceptionHandler
        implements ExceptionHandler<InvalidSourceConfigurationException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, InvalidSourceConfigurationException exception) {
        return HttpResponse.badRequest();
    }
}
