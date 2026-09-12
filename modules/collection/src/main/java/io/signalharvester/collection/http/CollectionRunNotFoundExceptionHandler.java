package io.signalharvester.collection.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.collection.run.CollectionRunNotFoundException;
import jakarta.inject.Singleton;

/** Maps unknown operational collection-run identifiers to HTTP 404. */
@Produces
@Singleton
public final class CollectionRunNotFoundExceptionHandler
        implements ExceptionHandler<CollectionRunNotFoundException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, CollectionRunNotFoundException exception) {
        return HttpResponse.notFound();
    }
}
