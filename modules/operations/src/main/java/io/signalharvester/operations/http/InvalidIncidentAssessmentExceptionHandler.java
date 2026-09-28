package io.signalharvester.operations.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.operations.assisted.InvalidIncidentAssessmentException;
import jakarta.inject.Singleton;

/** Maps structured-assessment validation failures without persisting untrusted raw model output. */
@Produces
@Singleton
public final class InvalidIncidentAssessmentExceptionHandler
        implements ExceptionHandler<InvalidIncidentAssessmentException, HttpResponse<String>> {
    @Override
    public HttpResponse<String> handle(HttpRequest request, InvalidIncidentAssessmentException exception) {
        return HttpResponse.badRequest("Invalid incident assessment");
    }
}
