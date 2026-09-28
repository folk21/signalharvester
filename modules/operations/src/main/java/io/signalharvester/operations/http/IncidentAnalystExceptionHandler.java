package io.signalharvester.operations.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.operations.assisted.IncidentAnalystException;
import jakarta.inject.Singleton;

/** Maps explicit provider transport/protocol failures without exposing provider payloads or credentials. */
@Produces
@Singleton
public final class IncidentAnalystExceptionHandler
        implements ExceptionHandler<IncidentAnalystException, HttpResponse<String>> {
    @Override
    public HttpResponse<String> handle(HttpRequest request, IncidentAnalystException exception) {
        return HttpResponse.<String>status(HttpStatus.BAD_GATEWAY).body("Assisted-investigation provider failed");
    }
}
