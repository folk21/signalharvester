package io.signalharvester.collection.source.http;

import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.exceptions.HttpClientException;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.signalharvester.collection.configuration.CollectionClockFactory;
import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.configuration.api.ConfiguredSource;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * Adapts the Micronaut-managed HTTP transport to the collection module's transport contract.
 *
 * <p>Status interpretation and collection-specific failures stay in this adapter rather than in
 * client filters. This keeps filters limited to cross-cutting transport concerns and avoids leaking
 * Micronaut HTTP exception types into collection application code.</p>
 */
@Singleton
public final class MicronautExternalSourceClient implements ExternalSourceClient {

    private static final String RETRY_AFTER = "Retry-After";

    private final ExternalSourceHttpClient httpClient;
    private final Clock clock;

    public MicronautExternalSourceClient(
            ExternalSourceHttpClient httpClient,
            @Named(CollectionClockFactory.COLLECTION_CLOCK) Clock clock) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Fetches a configured source and maps Micronaut transport outcomes to collection-owned types.
     *
     * @param source configured external source
     * @return accepted external-source response
     * @throws SourceFetchException when transport fails or a non-success response is received
     */
    @Override
    public FetchedSourceContent fetch(ConfiguredSource source) {
        Objects.requireNonNull(source, "source");

        try {
            HttpResponse<byte[]> response = httpClient.fetch(source.location().toASCIIString());
            if (response == null) {
                throw new SourceFetchException(
                        source.id(), source.location(), "External source client returned no response");
            }
            return toFetchedContent(source, response);
        } catch (HttpClientResponseException failure) {
            throw statusFailure(source, failure.getResponse());
        } catch (HttpClientException failure) {
            throw new SourceFetchException(
                    source.id(), source.location(), "External source request failed", failure);
        }
    }

    /**
     * Maps one transport response into the module-owned raw-content representation.
     */
    private FetchedSourceContent toFetchedContent(
            ConfiguredSource source, HttpResponse<byte[]> response) {
        if (response.code() < 200 || response.code() >= 300) {
            throw statusFailure(source, response);
        }

        byte[] body = Optional.ofNullable(response.body()).orElseGet(() -> new byte[0]);
        Optional<String> contentType = response.getContentType().map(Object::toString);
        return new FetchedSourceContent(
                source.id(), source.location(), response.code(), contentType, body, clock.instant());
    }

    /**
     * Converts an HTTP error response into a stable collection failure with retry metadata retained.
     */
    private SourceFetchException statusFailure(ConfiguredSource source, HttpResponse<?> response) {
        return new SourceFetchException(
                source.id(),
                source.location(),
                response.code(),
                Optional.ofNullable(response.header(RETRY_AFTER)));
    }
}
