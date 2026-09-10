package io.signalharvester.collection.configuration;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;

/**
 * Provides the UTC clock used to timestamp collection transport and run activity.
 *
 * <p>The clock is qualified for collection ownership so another module can introduce its own time
 * source without creating an ambiguous application-wide {@link Clock} bean.</p>
 */
@Factory
public final class CollectionClockFactory {

    /** Name used to qualify the collection transport clock. */
    public static final String COLLECTION_CLOCK = "collection-clock";

    /**
     * Creates the production collection clock.
     *
     * @return system UTC clock used by collection adapters and run orchestration
     */
    @Singleton
    @Named(COLLECTION_CLOCK)
    Clock collectionClock() {
        return Clock.systemUTC();
    }
}
