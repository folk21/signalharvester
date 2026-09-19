package io.signalharvester.analysis.event;

import static io.signalharvester.common.validation.Preconditions.requireNonBlankArgument;

import io.signalharvester.analysis.model.NormalizedContentItem;
import java.util.Objects;

/**
 * Analysis-owned terminal rejection ready for asynchronous publication.
 */
public record RejectedItem(NormalizedContentItem item, String reasonCode, String explanation) {

    public RejectedItem {
        Objects.requireNonNull(item, "item");
        requireNonBlankArgument(reasonCode, "reasonCode");
        requireNonBlankArgument(explanation, "explanation");
    }

}
