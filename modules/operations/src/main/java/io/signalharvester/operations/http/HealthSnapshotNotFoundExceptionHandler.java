package io.signalharvester.operations.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.operations.application.HealthSnapshotNotFoundException;
import jakarta.inject.Singleton;

/** Maps absence of any captured Health Snapshot to HTTP 404. */
@Produces
@Singleton
public final class HealthSnapshotNotFoundExceptionHandler
        implements ExceptionHandler<HealthSnapshotNotFoundException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, HealthSnapshotNotFoundException exception) {
        return HttpResponse.notFound();
    }
}
