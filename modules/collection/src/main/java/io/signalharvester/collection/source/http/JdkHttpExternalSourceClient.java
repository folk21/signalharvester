package io.signalharvester.collection.source.http;

import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.collection.source.SourceFetchException;
import io.signalharvester.configuration.api.ConfiguredSource;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Fetches raw external-source content with the JDK HTTP client.
 *
 * <p>The adapter deliberately owns only transport behavior. Response parsing, normalization, and
 * Kafka publication remain separate collection responsibilities. Request timeout and response-size
 * limits are explicit so a slow or unexpectedly large source cannot consume unbounded resources.</p>
 */
public final class JdkHttpExternalSourceClient implements ExternalSourceClient {

    private final HttpClient httpClient;
    private final Clock clock;
    private final Duration requestTimeout;
    private final int maxResponseBytes;

    /**
     * Creates an HTTP source client with explicit transport limits.
     *
     * @param httpClient configured JDK HTTP client
     * @param clock clock used for deterministic fetch timestamps
     * @param requestTimeout maximum duration of one HTTP request
     * @param maxResponseBytes maximum accepted response size in bytes
     */
    public JdkHttpExternalSourceClient(
            HttpClient httpClient,
            Clock clock,
            Duration requestTimeout,
            int maxResponseBytes) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");

        if (requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        if (maxResponseBytes <= 0) {
            throw new IllegalArgumentException("maxResponseBytes must be positive");
        }

        this.maxResponseBytes = maxResponseBytes;
    }

    /**
     * Fetches one configured source and accepts only bounded successful HTTP responses.
     *
     * @param source source configuration to fetch
     * @return immutable fetched-content value
     * @throws SourceFetchException for interruption, transport errors, non-success responses, or
     *     responses exceeding the configured size limit
     */
    @Override
    public FetchedSourceContent fetch(ConfiguredSource source) {
        Objects.requireNonNull(source, "source");

        HttpRequest request = HttpRequest.newBuilder(source.location())
                .timeout(requestTimeout)
                .header("Accept", "*/*")
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            return toFetchedContent(source, response);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SourceFetchException(source.id(), source.location(), "Source fetch was interrupted", exception);
        } catch (IOException exception) {
            throw new SourceFetchException(source.id(), source.location(), "Source fetch failed", exception);
        }
    }

    private FetchedSourceContent toFetchedContent(
            ConfiguredSource source,
            HttpResponse<InputStream> response) {
        try (InputStream body = response.body()) {
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new SourceFetchException(
                        source.id(),
                        source.location(),
                        "Source returned HTTP status " + response.statusCode());
            }

            byte[] bytes = body.readNBytes(maxResponseBytes + 1);
            if (bytes.length > maxResponseBytes) {
                throw new SourceFetchException(
                        source.id(),
                        source.location(),
                        "Source response exceeds " + maxResponseBytes + " bytes");
            }

            Optional<String> contentType = response.headers().firstValue("Content-Type");
            return new FetchedSourceContent(
                    source.id(),
                    source.location(),
                    response.statusCode(),
                    contentType,
                    bytes,
                    clock.instant());
        } catch (IOException exception) {
            throw new SourceFetchException(source.id(), source.location(), "Failed to read source response", exception);
        }
    }
}
