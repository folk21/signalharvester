package io.signalharvester.security.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.security.application.LastEnabledAdministratorException;
import jakarta.inject.Singleton;

/** Maps the last-enabled-administrator invariant to HTTP 409. */
@Produces
@Singleton
public final class LastEnabledAdministratorExceptionHandler
        implements ExceptionHandler<LastEnabledAdministratorException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, LastEnabledAdministratorException exception) {
        return HttpResponse.status(HttpStatus.CONFLICT);
    }
}
