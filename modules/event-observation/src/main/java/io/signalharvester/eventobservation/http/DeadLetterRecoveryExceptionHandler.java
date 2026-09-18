package io.signalharvester.eventobservation.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.eventobservation.application.DeadLetterRecoveryException;
import jakarta.inject.Singleton;
import java.util.Map;

/** Maps expected Event Observation dead-letter recovery failures to stable administrative HTTP statuses. */
@Produces
@Singleton
public final class DeadLetterRecoveryExceptionHandler
        implements ExceptionHandler<DeadLetterRecoveryException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, DeadLetterRecoveryException exception) {
        HttpStatus status = switch (exception.reason()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFIRMATION_FAILED, REPLAY_FAILED -> HttpStatus.CONFLICT;
            case INVALID_RECORD -> HttpStatus.UNPROCESSABLE_ENTITY;
            case BUSY -> HttpStatus.TOO_MANY_REQUESTS;
            case KAFKA_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return HttpResponse.status(status).body(Map.of("message", exception.getMessage()));
    }
}
