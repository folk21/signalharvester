package io.signalharvester.operations.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.operations.application.OperationalChangeNotFoundException;
import jakarta.inject.Singleton;

/** Maps missing operational change identifiers to HTTP 404. */
@Produces
@Singleton
public final class OperationalChangeNotFoundExceptionHandler
        implements ExceptionHandler<OperationalChangeNotFoundException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, OperationalChangeNotFoundException exception) {
        return HttpResponse.notFound();
    }
}
