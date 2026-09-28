package io.signalharvester.operations.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.operations.assisted.AssistedInvestigationUnavailableException;
import jakarta.inject.Singleton;

/** Maps disabled/missing provider configuration to a bounded service-unavailable response. */
@Produces
@Singleton
public final class AssistedInvestigationUnavailableExceptionHandler
        implements ExceptionHandler<AssistedInvestigationUnavailableException, HttpResponse<String>> {
    @Override
    public HttpResponse<String> handle(HttpRequest request, AssistedInvestigationUnavailableException exception) {
        return HttpResponse.<String>status(HttpStatus.SERVICE_UNAVAILABLE).body("Provider-assisted investigation is unavailable");
    }
}
