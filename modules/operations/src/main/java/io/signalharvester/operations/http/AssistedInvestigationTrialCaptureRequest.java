package io.signalharvester.operations.http;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Explicit request to capture one scenario-linked provider investigation as offline evaluation evidence. */
@Serdeable
public record AssistedInvestigationTrialCaptureRequest(
        @NotBlank @Size(max = 256) String scenarioRunId,
        @NotNull UUID snapshotId) {}
