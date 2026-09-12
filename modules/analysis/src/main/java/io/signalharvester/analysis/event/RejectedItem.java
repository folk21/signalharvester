package io.signalharvester.analysis.event;

import io.signalharvester.analysis.model.NormalizedContentItem;
import java.util.Objects;

/**
 * Analysis-owned terminal rejection ready for asynchronous publication.
 */
public record RejectedItem(NormalizedContentItem item, String reasonCode, String explanation) {

    public RejectedItem {
        Objects.requireNonNull(item, "item");
        requireNonBlank(reasonCode, "reasonCode");
        requireNonBlank(explanation, "explanation");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
