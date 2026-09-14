package io.signalharvester.collection.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.signalharvester.collection.run.CollectionProfileNotFoundException;
import jakarta.inject.Singleton;

/** Maps a missing persisted monitoring profile to the public HTTP 404 contract. */
@Produces
@Singleton
public final class CollectionProfileNotFoundExceptionHandler
        implements ExceptionHandler<CollectionProfileNotFoundException, HttpResponse<?>> {
    @Override
    public HttpResponse<?> handle(HttpRequest request, CollectionProfileNotFoundException exception) {
        return HttpResponse.notFound();
    }
}
