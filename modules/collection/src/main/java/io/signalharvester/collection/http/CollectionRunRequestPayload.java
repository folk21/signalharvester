package io.signalharvester.collection.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.collection.run.CollectionRunRequest;
import io.signalharvester.configuration.api.MonitoringProfileId;
import jakarta.validation.constraints.NotNull;
import java.util.Optional;
import java.util.UUID;

/** REST payload for manually starting one profile-owned collection run. */
@Serdeable
public record CollectionRunRequestPayload(@NotNull UUID monitoringProfileId) {

    CollectionRunRequest toRunRequest() {
        return new CollectionRunRequest(MonitoringProfileId.of(monitoringProfileId), Optional.empty());
    }
}
