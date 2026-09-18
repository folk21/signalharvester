package io.signalharvester.analysis.configuration;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.Context;
import io.micronaut.core.bind.annotation.Bindable;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;

/** Provides bounded retry and dead-letter settings for the Analysis Kafka consumer. */
@Context
@ConfigurationProperties("signalharvester.analysis.kafka-reliability")
public interface AnalysisKafkaReliabilityConfiguration {

    /** Returns the maximum processing attempts including the initial attempt. */
    @Positive
    @Max(10)
    @Bindable(defaultValue = "3")
    int getMaxAttempts();

    /** Returns the fixed delay between retryable attempts; listeners accept zero through five seconds. */
    @NotNull
    @Bindable(defaultValue = "250ms")
    Duration getRetryBackoff();

    /** Returns the topic that receives terminal Analysis consumer failures. */
    @NotBlank
    @Bindable(defaultValue = "signalharvester.analysis.raw-item-dead-letter.v1")
    String getDeadLetterTopic();

    /** Returns the maximum Kafka wait used when an operator inspects one dead-letter record. */
    @NotNull
    @Bindable(defaultValue = "2s")
    Duration getReplayReadTimeout();

    /** Returns the maximum concurrent operator dead-letter inspection/replay operations for Analysis. */
    @Positive
    @Max(4)
    @Bindable(defaultValue = "1")
    int getReplayMaxConcurrency();
}
