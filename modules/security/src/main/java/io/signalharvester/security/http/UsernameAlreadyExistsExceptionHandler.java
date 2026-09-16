package io.signalharvester.security.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.security.application.UsernameAlreadyExistsException;
import jakarta.inject.Singleton;

/** Maps case-insensitive username conflicts to HTTP 409. */
@Produces
@Singleton
public final class UsernameAlreadyExistsExceptionHandler
        implements ExceptionHandler<UsernameAlreadyExistsException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, UsernameAlreadyExistsException exception) {
        return HttpResponse.status(HttpStatus.CONFLICT);
    }
}
