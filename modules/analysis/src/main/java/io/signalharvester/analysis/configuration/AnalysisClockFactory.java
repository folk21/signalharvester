package io.signalharvester.analysis.configuration;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.time.Clock;

/**
 * Provides the analysis-owned UTC clock used for deduplication observations and event timestamps.
 */
@Factory
public final class AnalysisClockFactory {

    /** Name used to qualify the analysis module clock. */
    public static final String ANALYSIS_CLOCK = "analysis-clock";

    @Singleton
    @Named(ANALYSIS_CLOCK)
    Clock analysisClock() {
        return Clock.systemUTC();
    }
}
