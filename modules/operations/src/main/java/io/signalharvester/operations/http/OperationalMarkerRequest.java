package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import io.signalharvester.operations.api.OperationalChangeCategory;
import io.signalharvester.operations.api.OperationalChangeTargetType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;

/** Bounded safe marker emitted by repository-owned operational tooling or deterministic scenarios. */
@Serdeable
public record OperationalMarkerRequest(
        @NotNull OperationalChangeCategory category,
        @NotNull OperationalChangeTargetType targetType,
        @NotBlank @Size(max = 256) String targetId,
        @Size(max = 32) Map<@NotBlank @Size(max = 64) String, @NotNull @Size(max = 512) String> details) {

    public OperationalMarkerRequest {
        details = details == null ? Map.of() : Map.copyOf(details);
    }
}
