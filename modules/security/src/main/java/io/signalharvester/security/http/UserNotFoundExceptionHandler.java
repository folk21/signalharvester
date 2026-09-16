package io.signalharvester.security.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.security.application.UserNotFoundException;
import jakarta.inject.Singleton;

/** Maps missing security identities to HTTP 404. */
@Produces
@Singleton
public final class UserNotFoundExceptionHandler implements ExceptionHandler<UserNotFoundException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, UserNotFoundException exception) {
        return HttpResponse.notFound();
    }
}
