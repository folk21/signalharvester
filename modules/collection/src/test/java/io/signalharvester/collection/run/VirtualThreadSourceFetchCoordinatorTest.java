package io.signalharvester.collection.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.signalharvester.collection.source.ExternalSourceClient;
import io.signalharvester.collection.source.FetchedSourceContent;
import io.signalharvester.configuration.api.ConfiguredSource;
import io.signalharvester.configuration.api.SourceId;
import io.signalharvester.configuration.api.SourceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

/**
 * Verifies deterministic batch ordering and Virtual Thread execution for source fetches.
 */
class VirtualThreadSourceFetchCoordinatorTest {

    @Test
    void shouldFetchSourcesOnVirtualThreadsAndPreserveInputOrder() {
        RecordingSourceClient sourceClient = new RecordingSourceClient();
        VirtualThreadSourceFetchCoordinator coordinator = new VirtualThreadSourceFetchCoordinator(sourceClient, 2);
        List<ConfiguredSource> sources = List.of(
                source("source-a", "c40b31d8-eb0b-4e88-b7f2-5f0387546894"),
                source("source-b", "07bb086c-f0c1-4be1-a8ef-8bd0d97200f3"),
                source("source-c", "12dcb9ed-bc63-4a46-8688-47d3fb3e34df"));

        List<FetchedSourceContent> results = coordinator.fetchAll(sources);

        assertEquals(sources.stream().map(ConfiguredSource::id).toList(), results.stream().map(FetchedSourceContent::sourceId).toList());
        assertEquals(3, sourceClient.virtualThreadFlags.size());
        assertTrue(sourceClient.virtualThreadFlags.stream().allMatch(Boolean::booleanValue));
    }

    private ConfiguredSource source(String name, String id) {
        return new ConfiguredSource(
                SourceId.of(UUID.fromString(id)),
                name,
                SourceType.REST,
                URI.create("https://example.invalid/" + name),
                true,
                Map.of());
    }

    /**
     * Records the execution-thread type while returning deterministic synthetic responses.
     */
    private static final class RecordingSourceClient implements ExternalSourceClient {

        private final List<Boolean> virtualThreadFlags = new CopyOnWriteArrayList<>();

        @Override
        public FetchedSourceContent fetch(ConfiguredSource source) {
            virtualThreadFlags.add(Thread.currentThread().isVirtual());
            return new FetchedSourceContent(
                    source.id(),
                    source.location(),
                    200,
                    Optional.of("application/json"),
                    source.name().getBytes(StandardCharsets.UTF_8),
                    Instant.EPOCH);
        }
    }
}
