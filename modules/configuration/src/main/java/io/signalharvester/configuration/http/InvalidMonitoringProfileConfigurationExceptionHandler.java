package io.signalharvester.configuration.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.configuration.application.InvalidMonitoringProfileConfigurationException;
import jakarta.inject.Singleton;
import java.util.Map;

/** Maps invalid monitoring-profile configuration to HTTP 400. */
@Produces
@Singleton
public final class InvalidMonitoringProfileConfigurationExceptionHandler
        implements ExceptionHandler<InvalidMonitoringProfileConfigurationException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, InvalidMonitoringProfileConfigurationException exception) {
        return HttpResponse.badRequest(Map.of("message", exception.getMessage()));
    }
}
