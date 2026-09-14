package io.signalharvester.configuration.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.configuration.application.MonitoringProfileNotFoundException;
import jakarta.inject.Singleton;

/** Maps missing monitoring profiles to HTTP 404. */
@Produces
@Singleton
public final class MonitoringProfileNotFoundExceptionHandler
        implements ExceptionHandler<MonitoringProfileNotFoundException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, MonitoringProfileNotFoundException exception) {
        return HttpResponse.notFound();
    }
}
