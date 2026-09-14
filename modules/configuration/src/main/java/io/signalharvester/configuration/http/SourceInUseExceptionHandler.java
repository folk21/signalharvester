package io.signalharvester.configuration.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.configuration.application.SourceInUseException;
import jakarta.inject.Singleton;

/** Maps an attempted deletion of a profile-owned source to HTTP 409. */
@Produces
@Singleton
public final class SourceInUseExceptionHandler implements ExceptionHandler<SourceInUseException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, SourceInUseException exception) {
        return HttpResponse.status(HttpStatus.CONFLICT);
    }
}
