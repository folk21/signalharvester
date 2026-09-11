package io.signalharvester.analysis.application;

import io.signalharvester.analysis.model.DiscoveredRawItem;

/**
 * Application boundary invoked by the raw-item Kafka adapter after successful transport decoding.
 */
public interface RawItemProcessor {

    /**
     * Normalizes, deduplicates, analyzes or rejects, and publishes one terminal processing event.
     *
     * @param rawItem decoded raw discovery
     * @return terminal processing/publication result
     */
    RawItemProcessingResult process(DiscoveredRawItem rawItem);
}
