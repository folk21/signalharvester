package io.signalharvester.collection.source.http;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.BlockingHttpClient;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.HttpClientRegistry;
import jakarta.inject.Singleton;
import java.net.URI;
import java.util.Objects;

/**
 * Uses Micronaut's managed default HTTP client for requests to dynamically configured absolute URLs.
 *
 * <p>The underlying Netty client and its lifecycle remain owned by Micronaut. This adapter only
 * exposes its blocking facade because collection work runs on {@code TaskExecutors.BLOCKING}; it
 * must never be invoked from a Netty event-loop thread.</p>
 */
@Singleton
public final class MicronautManagedExternalSourceHttpClient implements ExternalSourceHttpClient {

    private final BlockingHttpClient blockingHttpClient;

    public MicronautManagedExternalSourceHttpClient(HttpClientRegistry<HttpClient> clientRegistry) {
        Objects.requireNonNull(clientRegistry, "clientRegistry");
        this.blockingHttpClient = clientRegistry.getDefaultClient().toBlocking();
    }

    /**
     * Executes one absolute GET request using the Micronaut-managed client configuration and filters.
     *
     * @param uri absolute source URL
     * @return complete HTTP response with a byte-array body
     */
    @Override
    public HttpResponse<byte[]> fetch(URI uri) {
        MutableHttpRequest<Object> request = HttpRequest.GET(Objects.requireNonNull(uri, "uri"));
        request.setAttribute(ExternalSourceHttpFilter.SOURCE_REQUEST_ATTRIBUTE, true);
        return blockingHttpClient.exchange(request, byte[].class);
    }
}
