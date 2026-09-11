package io.signalharvester.analysis.normalization;

import io.signalharvester.analysis.model.DiscoveredRawItem;
import io.signalharvester.analysis.model.NormalizedContentItem;

/**
 * Converts decoded raw discoveries into the common analysis content model.
 */
public interface ContentNormalizer {

    /**
     * Produces deterministic normalized content and logical identity while preserving provenance.
     *
     * @param rawItem decoded raw item
     * @return normalized common representation
     */
    NormalizedContentItem normalize(DiscoveredRawItem rawItem);
}
