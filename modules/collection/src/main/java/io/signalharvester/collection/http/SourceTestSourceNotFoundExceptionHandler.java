package io.signalharvester.collection.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.collection.sourcetest.SourceTestSourceNotFoundException;
import jakarta.inject.Singleton;

/** Maps a missing configured source during diagnostic testing to HTTP 404. */
@Produces
@Singleton
public final class SourceTestSourceNotFoundExceptionHandler
        implements ExceptionHandler<SourceTestSourceNotFoundException, HttpResponse<?>> {

    @Override
    public HttpResponse<?> handle(HttpRequest request, SourceTestSourceNotFoundException exception) {
        return HttpResponse.notFound();
    }
}
