package io.signalharvester.collection.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.collection.api.CollectionRunRequest;
import jakarta.validation.constraints.NotBlank;
import java.util.Optional;

/** REST payload for manually starting one collection run. */
@Serdeable
public record CollectionRunRequestPayload(
        @NotBlank String monitoringProfileId,
        @NotBlank String informationCategory) {

    CollectionRunRequest toRunRequest() {
        return new CollectionRunRequest(monitoringProfileId, informationCategory, Optional.empty());
    }
}
